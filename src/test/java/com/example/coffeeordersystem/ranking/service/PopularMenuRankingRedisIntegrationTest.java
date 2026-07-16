package com.example.coffeeordersystem.ranking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.example.coffeeordersystem.order.repository.OrderRepository;
import com.example.coffeeordersystem.order.repository.OrderEventRepository;
import com.example.coffeeordersystem.ranking.dto.DailyMenuOrderCount;
import com.example.coffeeordersystem.ranking.dto.PopularMenuRanking;
import com.example.coffeeordersystem.ranking.dto.RebuildOrderEvent;
import com.example.coffeeordersystem.ranking.redis.RedisRankingAggregationService;
import com.example.coffeeordersystem.ranking.redis.RedisRankingKey;
import com.example.coffeeordersystem.outbox.dto.OrderPaidEvent;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class PopularMenuRankingRedisIntegrationTest {

	@Container
	private static final GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
		.withExposedPorts(6379);

	private static LettuceConnectionFactory connectionFactory;
	private static final ScheduledExecutorService leaseScheduler = Executors.newSingleThreadScheduledExecutor();
	private StringRedisTemplate redisTemplate;
	private OrderRepository orderRepository;
	private OrderEventRepository orderEventRepository;
	private PopularMenuRankingService service;

	@BeforeEach
	void setUp() {
		connectionFactory = new LettuceConnectionFactory(redis.getHost(), redis.getFirstMappedPort());
		connectionFactory.afterPropertiesSet();
		redisTemplate = new StringRedisTemplate(connectionFactory);
		redisTemplate.afterPropertiesSet();
		redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
		orderRepository = org.mockito.Mockito.mock(OrderRepository.class);
		orderEventRepository = org.mockito.Mockito.mock(OrderEventRepository.class);
		DefaultRedisScript<Long> releaseLockScript = new DefaultRedisScript<>();
		releaseLockScript.setScriptText("if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end return 0");
		releaseLockScript.setResultType(Long.class);
		DefaultRedisScript<Long> renewLockScript = new DefaultRedisScript<>(
			"if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('EXPIRE', KEYS[1], ARGV[2]) end return 0", Long.class
		);
		DefaultRedisScript<Long> cleanupMarkerScript = new DefaultRedisScript<>(
			"if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end return 0", Long.class
		);
		DefaultRedisScript<Long> cleanupRebuildScript = new DefaultRedisScript<>(
			"if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', unpack(KEYS, 2)) end return 0", Long.class
		);
		service = new PopularMenuRankingService(
			redisTemplate, orderRepository, orderEventRepository, Duration.ofDays(8), Duration.ofMinutes(1), Duration.ofSeconds(20),
			releaseLockScript, renewLockScript, cleanupMarkerScript, cleanupRebuildScript, rebuildWriteScript(), leaseScheduler,
			Clock.fixed(Instant.parse("2026-07-15T01:00:00Z"), ZoneId.of("Asia/Seoul"))
		);
	}

	@AfterAll
	static void tearDown() {
		if (connectionFactory != null) {
			connectionFactory.destroy();
		}
		leaseScheduler.shutdownNow();
	}

	@Test
	void 실제_Redis에서_7일_점수를_합산한다() {
		// given
		redisTemplate.opsForZSet().incrementScore(RedisRankingKey.dailyRanking(LocalDate.of(2026, 7, 15)), "8", 2);
		redisTemplate.opsForZSet().incrementScore(RedisRankingKey.dailyRanking(LocalDate.of(2026, 7, 14)), "8", 3);
		redisTemplate.opsForZSet().incrementScore(RedisRankingKey.dailyRanking(LocalDate.of(2026, 7, 14)), "2", 5);
		for (int offset = 0; offset < 7; offset++) {
			LocalDate date = LocalDate.of(2026, 7, 15).minusDays(offset);
			redisTemplate.opsForValue().set(RedisRankingKey.dailyStatus(date), offset < 2 ? "DATA" : "EMPTY", Duration.ofDays(8));
		}

		// when
		List<PopularMenuRanking> rankings = service.getPopularMenuRankings();

		// then
		assertThat(rankings).containsExactly(new PopularMenuRanking(2L, 5L), new PopularMenuRanking(8L, 5L));
	}

	@Test
	void 실제_Redis가_비어있으면_DB_집계로_일자별_ZSET을_복구한다() {
		// given
		when(orderRepository.findDailyPaidMenuOrderCounts(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
			.thenReturn(List.of(new DailyMenuOrderCount(LocalDate.of(2026, 7, 15), 4L, 2L)));

		// when
		List<PopularMenuRanking> rankings = service.getPopularMenuRankings();

		// then
		assertThat(rankings).containsExactly(new PopularMenuRanking(4L, 2L));
		assertThat(redisTemplate.opsForZSet().score(RedisRankingKey.dailyRanking(LocalDate.of(2026, 7, 15)), "4"))
			.isEqualTo(2D);
		assertThat(redisTemplate.getExpire(RedisRankingKey.dailyRanking(LocalDate.of(2026, 7, 15)))).isPositive();
	}

	@Test
	void 실제_Redis에서_일치한_토큰만_lease를_연장하고_잠금을_해제한다() {
		// given
		String ownerToken = "owner-token";
		String otherToken = "other-token";
		redisTemplate.opsForValue().set(RedisRankingKey.rebuilding(), ownerToken, Duration.ofSeconds(2));
		DefaultRedisScript<Long> renewLockScript = script(
			"if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('EXPIRE', KEYS[1], ARGV[2]) end return 0"
		);
		DefaultRedisScript<Long> releaseLockScript = script(
			"if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end return 0"
		);

		// when
		Long renewedByOwner = redisTemplate.execute(renewLockScript, List.of(RedisRankingKey.rebuilding()), ownerToken, "2");
		Long renewedByOther = redisTemplate.execute(renewLockScript, List.of(RedisRankingKey.rebuilding()), otherToken, "2");
		Long releasedByOther = redisTemplate.execute(releaseLockScript, List.of(RedisRankingKey.rebuilding()), otherToken);

		// then
		assertThat(renewedByOwner).isEqualTo(1L);
		assertThat(renewedByOther).isEqualTo(0L);
		assertThat(releasedByOther).isEqualTo(0L);
		assertThat(redisTemplate.opsForValue().get(RedisRankingKey.rebuilding())).isEqualTo(ownerToken);
	}

	@Test
	void 장시간_재구성_중_Consumer는_재시도하고_완료_후_이벤트는_한번만_반영된다() throws Exception {
		// given
		CountDownLatch rebuildQueryStarted = new CountDownLatch(1);
		when(orderRepository.findDailyPaidMenuOrderCounts(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
			.thenAnswer(invocation -> {
				rebuildQueryStarted.countDown();
				Thread.sleep(2_300L);
				return List.of(new DailyMenuOrderCount(LocalDate.of(2026, 7, 15), 7L, 1L));
			});
		when(orderEventRepository.findPaidEventsForRankingRebuild(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
			.thenReturn(List.of(new RebuildOrderEvent(42L)));
		PopularMenuRankingService longRebuildService = rebuildService(Duration.ofSeconds(2), Duration.ofSeconds(1));
		RedisRankingAggregationService aggregationService = new RedisRankingAggregationService(
			redisTemplate, Duration.ofDays(8), Clock.fixed(Instant.parse("2026-07-15T01:00:00Z"), ZoneId.of("Asia/Seoul")),
			processOnceScript()
		);
		OrderPaidEvent event = new OrderPaidEvent(42L, 10L, 3L, 7L, 4_500, LocalDateTime.of(2026, 7, 15, 10, 0));
		ExecutorService rebuildExecutor = Executors.newSingleThreadExecutor();

		// when
		java.util.concurrent.Future<List<PopularMenuRanking>> rebuild = rebuildExecutor.submit(longRebuildService::getPopularMenuRankings);
		assertThat(rebuildQueryStarted.await(5, TimeUnit.SECONDS)).isTrue();
		org.assertj.core.api.ThrowableAssert.ThrowingCallable aggregateDuringRebuild = () -> aggregationService.aggregate(event);

		// then
		org.assertj.core.api.Assertions.assertThatThrownBy(aggregateDuringRebuild)
			.isInstanceOf(IllegalStateException.class).hasMessage("랭킹 Redis 재구성 중입니다.");
		assertThat(rebuild.get(5, TimeUnit.SECONDS)).containsExactly(new PopularMenuRanking(7L, 1L));
		assertThat(aggregationService.aggregate(event)).isFalse();
		assertThat(redisTemplate.opsForZSet().score(RedisRankingKey.dailyRanking(LocalDate.of(2026, 7, 15)), "7")).isEqualTo(1D);
		rebuildExecutor.shutdownNow();
	}

	private PopularMenuRankingService rebuildService(Duration lockTtl, Duration renewInterval) {
		return new PopularMenuRankingService(
			redisTemplate, orderRepository, orderEventRepository, Duration.ofDays(8), lockTtl, renewInterval,
			script("if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end return 0"),
			script("if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('EXPIRE', KEYS[1], ARGV[2]) end return 0"),
			script("if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end return 0"),
			script("if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', unpack(KEYS, 2)) end return 0"),
			rebuildWriteScript(),
			leaseScheduler, Clock.fixed(Instant.parse("2026-07-15T01:00:00Z"), ZoneId.of("Asia/Seoul"))
		);
	}

	private DefaultRedisScript<Long> processOnceScript() {
		DefaultRedisScript<Long> script = new DefaultRedisScript<>();
		script.setLocation(new org.springframework.core.io.ClassPathResource("scripts/ranking-process-once.lua"));
		script.setResultType(Long.class);
		return script;
	}

	private DefaultRedisScript<Long> rebuildWriteScript() {
		DefaultRedisScript<Long> script = new DefaultRedisScript<>();
		script.setLocation(new org.springframework.core.io.ClassPathResource("scripts/ranking-rebuild-write.lua"));
		script.setResultType(Long.class);
		return script;
	}

	private DefaultRedisScript<Long> script(String source) {
		return new DefaultRedisScript<>(source, Long.class);
	}
}
