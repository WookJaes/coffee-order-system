package com.example.coffeeordersystem.ranking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

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
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
@Execution(ExecutionMode.SAME_THREAD)
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
		redisTemplate.delete(RedisRankingKey.rebuilding());
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
			releaseLockScript, renewLockScript, cleanupMarkerScript, cleanupRebuildScript, rebuildWriteScript(), readSnapshotScript(), leaseScheduler,
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
		when(orderRepository.findDailyPaidMenuOrderCounts(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
			.thenReturn(List.of(
				new DailyMenuOrderCount(LocalDate.of(2026, 7, 15), 8L, 2L),
				new DailyMenuOrderCount(LocalDate.of(2026, 7, 14), 8L, 3L),
				new DailyMenuOrderCount(LocalDate.of(2026, 7, 14), 2L, 5L)
			));
		redisTemplate.opsForZSet().incrementScore(RedisRankingKey.dailyRanking(LocalDate.of(2026, 7, 15)), "8", 2);
		redisTemplate.opsForZSet().incrementScore(RedisRankingKey.dailyRanking(LocalDate.of(2026, 7, 14)), "8", 3);
		redisTemplate.opsForZSet().incrementScore(RedisRankingKey.dailyRanking(LocalDate.of(2026, 7, 14)), "2", 5);
		for (int offset = 0; offset < 7; offset++) {
			LocalDate date = LocalDate.of(2026, 7, 15).minusDays(offset);
			redisTemplate.opsForValue().set(RedisRankingKey.dailyStatus(date), offset < 2 ? "DATA" : "EMPTY", Duration.ofDays(8));
			redisTemplate.opsForValue().set(
				RedisRankingKey.dailyProcessedOrderCount(date),
				offset == 0 ? "2" : offset == 1 ? "8" : "0",
				Duration.ofDays(8)
			);
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
		assertThat(redisTemplate.opsForValue().get(
			RedisRankingKey.dailyProcessedOrderCount(LocalDate.of(2026, 7, 15))
		)).isEqualTo("2");
		for (int offset = 1; offset < 7; offset++) {
			LocalDate date = LocalDate.of(2026, 7, 15).minusDays(offset);
			assertThat(redisTemplate.opsForValue().get(RedisRankingKey.dailyProcessedOrderCount(date))).isEqualTo("0");
			assertThat(redisTemplate.opsForValue().get(RedisRankingKey.dailyStatus(date))).isEqualTo("EMPTY");
			assertThat(redisTemplate.hasKey(RedisRankingKey.dailyRanking(date))).isFalse();
		}
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
			.thenReturn(List.of(new DailyMenuOrderCount(LocalDate.of(2026, 7, 15), 7L, 1L)));
		when(orderEventRepository.findPaidEventsForRankingRebuild(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
			.thenAnswer(invocation -> {
				rebuildQueryStarted.countDown();
				Thread.sleep(2_300L);
				return List.of(new RebuildOrderEvent(42L));
			});
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

	@Test
	void Redis_점수와_count의_원자_snapshot은_Consumer_갱신과_섞이지_않는다() throws Exception {
		// given
		LocalDate date = LocalDate.of(2026, 7, 15);
		redisTemplate.opsForZSet().add(RedisRankingKey.dailyRanking(date), "7", 1D);
		redisTemplate.opsForValue().set(RedisRankingKey.dailyProcessedOrderCount(date), "1", Duration.ofDays(8));
		redisTemplate.opsForValue().set(RedisRankingKey.dailyStatus(date), "DATA", Duration.ofDays(8));
		RedisRankingAggregationService aggregationService = new RedisRankingAggregationService(
			redisTemplate, Duration.ofDays(8), Clock.fixed(Instant.parse("2026-07-15T01:00:00Z"), ZoneId.of("Asia/Seoul")),
			processOnceScript()
		);
		List<String> keys = List.of(
			RedisRankingKey.dailyRanking(date), RedisRankingKey.dailyStatus(date), RedisRankingKey.dailyProcessedOrderCount(date),
			RedisRankingKey.dailyRanking(date.minusDays(1)), RedisRankingKey.dailyStatus(date.minusDays(1)), RedisRankingKey.dailyProcessedOrderCount(date.minusDays(1)),
			RedisRankingKey.dailyRanking(date.minusDays(2)), RedisRankingKey.dailyStatus(date.minusDays(2)), RedisRankingKey.dailyProcessedOrderCount(date.minusDays(2)),
			RedisRankingKey.dailyRanking(date.minusDays(3)), RedisRankingKey.dailyStatus(date.minusDays(3)), RedisRankingKey.dailyProcessedOrderCount(date.minusDays(3)),
			RedisRankingKey.dailyRanking(date.minusDays(4)), RedisRankingKey.dailyStatus(date.minusDays(4)), RedisRankingKey.dailyProcessedOrderCount(date.minusDays(4)),
			RedisRankingKey.dailyRanking(date.minusDays(5)), RedisRankingKey.dailyStatus(date.minusDays(5)), RedisRankingKey.dailyProcessedOrderCount(date.minusDays(5)),
			RedisRankingKey.dailyRanking(date.minusDays(6)), RedisRankingKey.dailyStatus(date.minusDays(6)), RedisRankingKey.dailyProcessedOrderCount(date.minusDays(6))
		);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		java.util.concurrent.Future<List<String>> snapshots = executor.submit(() -> {
			start.await();
			List<String> values = new java.util.ArrayList<>();
			for (int attempt = 0; attempt < 100; attempt++) {
				values.add(redisTemplate.execute(readSnapshotScript(), keys, new Object[0]));
			}
			return values;
		});
		java.util.concurrent.Future<?> consumer = executor.submit(() -> {
			start.await();
			aggregationService.aggregate(new OrderPaidEvent(43L, 11L, 3L, 8L, 4_500,
				LocalDateTime.of(2026, 7, 15, 10, 0)));
			return null;
		});

		// when
		start.countDown();
		List<String> values = snapshots.get(5, TimeUnit.SECONDS);
		consumer.get(5, TimeUnit.SECONDS);
		executor.shutdownNow();

		// then
		assertThat(values).allSatisfy(value -> assertThat(value)
			.isIn(
				"DATA|1|1|7=1;||0|;||0|;||0|;||0|;||0|;||0|",
				"DATA|2|1|7=1,8=1;||0|;||0|;||0|;||0|;||0|;||0|"
			));
	}

	@Test
	void 여러_서버가_동시에_재구성해도_한_서버만_잠금을_획득하고_둘_다_DB_결과를_반환한다() throws Exception {
		// given
		CountDownLatch rebuildStarted = new CountDownLatch(1);
		CountDownLatch releaseRebuild = new CountDownLatch(1);
		when(orderRepository.findDailyPaidMenuOrderCounts(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
			.thenReturn(List.of(new DailyMenuOrderCount(LocalDate.of(2026, 7, 15), 7L, 1L)));
		when(orderEventRepository.findPaidEventsForRankingRebuild(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
			.thenAnswer(invocation -> {
				rebuildStarted.countDown();
				releaseRebuild.await(5, TimeUnit.SECONDS);
				return List.of();
			});
		PopularMenuRankingService first = rebuildService(Duration.ofMinutes(1), Duration.ofSeconds(20));
		PopularMenuRankingService second = rebuildService(Duration.ofMinutes(1), Duration.ofSeconds(20));
		ExecutorService executor = Executors.newFixedThreadPool(2);

		// when
		java.util.concurrent.Future<List<PopularMenuRanking>> firstResult = executor.submit(first::getPopularMenuRankings);
		assertThat(rebuildStarted.await(5, TimeUnit.SECONDS)).isTrue();
		java.util.concurrent.Future<List<PopularMenuRanking>> secondResult = executor.submit(second::getPopularMenuRankings);
		releaseRebuild.countDown();

		// then
		assertThat(firstResult.get(5, TimeUnit.SECONDS)).containsExactly(new PopularMenuRanking(7L, 1L));
		assertThat(secondResult.get(5, TimeUnit.SECONDS)).containsExactly(new PopularMenuRanking(7L, 1L));
		verify(orderEventRepository, org.mockito.Mockito.times(1))
			.findPaidEventsForRankingRebuild(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
		assertThat(redisTemplate.opsForValue().get(RedisRankingKey.dailyProcessedOrderCount(LocalDate.of(2026, 7, 15))))
			.isEqualTo("1");
		executor.shutdownNow();
	}

	private PopularMenuRankingService rebuildService(Duration lockTtl, Duration renewInterval) {
		return new PopularMenuRankingService(
			redisTemplate, orderRepository, orderEventRepository, Duration.ofDays(8), lockTtl, renewInterval,
			script("if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end return 0"),
			script("if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('EXPIRE', KEYS[1], ARGV[2]) end return 0"),
			script("if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end return 0"),
			script("if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', unpack(KEYS, 2)) end return 0"),
			rebuildWriteScript(), readSnapshotScript(),
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

	private DefaultRedisScript<String> readSnapshotScript() {
		DefaultRedisScript<String> script = new DefaultRedisScript<>();
		script.setLocation(new org.springframework.core.io.ClassPathResource("scripts/ranking-read-snapshot.lua"));
		script.setResultType(String.class);
		return script;
	}

	private DefaultRedisScript<Long> script(String source) {
		return new DefaultRedisScript<>(source, Long.class);
	}
}
