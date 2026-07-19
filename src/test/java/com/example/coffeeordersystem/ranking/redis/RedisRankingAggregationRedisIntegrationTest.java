package com.example.coffeeordersystem.ranking.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.coffeeordersystem.outbox.dto.OrderPaidEvent;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
@Execution(ExecutionMode.SAME_THREAD)
class RedisRankingAggregationRedisIntegrationTest {

	@Container
	private static final GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
		.withExposedPorts(6379);

	private static final Duration KEY_TTL = Duration.ofDays(8);
	private static LettuceConnectionFactory connectionFactory;
	private RedisRankingAggregationService service;
	private StringRedisTemplate redisTemplate;

	@BeforeEach
	void setUp() {
		connectionFactory = new LettuceConnectionFactory(redis.getHost(), redis.getFirstMappedPort());
		connectionFactory.afterPropertiesSet();
		redisTemplate = new StringRedisTemplate(connectionFactory);
		redisTemplate.afterPropertiesSet();
		redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
		redisTemplate.delete(RedisRankingKey.rebuilding());

		DefaultRedisScript<Long> script = new DefaultRedisScript<>();
		script.setLocation(new ClassPathResource("scripts/ranking-process-once.lua"));
		script.setResultType(Long.class);
		service = new RedisRankingAggregationService(
			redisTemplate,
			KEY_TTL,
			Clock.fixed(Instant.parse("2026-07-15T01:00:00Z"), ZoneId.of("Asia/Seoul")),
			script
		);
	}

	@AfterAll
	static void tearDown() {
		if (connectionFactory != null) {
			connectionFactory.destroy();
		}
	}

	@Test
	void 자정_이후_지연_소비와_재소비에도_주문일_ZSET_점수는_한번만_증가하고_TTL이_설정된다() {
		// given
		OrderPaidEvent event = new OrderPaidEvent(42L, 10L, 3L, 7L, 4_500, LocalDateTime.of(2026, 7, 14, 23, 59, 59));
		String rankingKey = RedisRankingKey.dailyRanking(java.time.LocalDate.of(2026, 7, 14));
		String processedKey = RedisRankingKey.processedEvent(event.eventId());

		// when
		boolean firstAggregated = service.aggregate(event);
		boolean duplicateAggregated = service.aggregate(event);

		// then
		assertThat(firstAggregated).isTrue();
		assertThat(duplicateAggregated).isFalse();
		assertThat(redisTemplate.opsForZSet().score(rankingKey, "7")).isEqualTo(1.0);
		assertThat(redisTemplate.opsForValue().get(
			RedisRankingKey.dailyProcessedOrderCount(java.time.LocalDate.of(2026, 7, 14))
		)).isEqualTo("1");
		assertThat(redisTemplate.opsForZSet().score(
			RedisRankingKey.dailyRanking(java.time.LocalDate.of(2026, 7, 15)), "7"
		)).isNull();
		assertThat(redisTemplate.getExpire(rankingKey)).isPositive().isLessThanOrEqualTo(KEY_TTL.getSeconds());
		assertThat(redisTemplate.getExpire(processedKey)).isPositive().isLessThanOrEqualTo(KEY_TTL.getSeconds());
		String dailyCountKey = RedisRankingKey.dailyProcessedOrderCount(java.time.LocalDate.of(2026, 7, 14));
		String dailyStatusKey = RedisRankingKey.dailyStatus(java.time.LocalDate.of(2026, 7, 14));
		assertThat(redisTemplate.getExpire(dailyCountKey)).isPositive().isLessThanOrEqualTo(KEY_TTL.getSeconds());
		assertThat(redisTemplate.getExpire(dailyStatusKey)).isPositive().isLessThanOrEqualTo(KEY_TTL.getSeconds());
	}

	@Test
	void 실제_Redis_재구성_잠금이_있으면_집계를_중단한다() {
		// given
		redisTemplate.opsForValue().set(RedisRankingKey.rebuilding(), "1", Duration.ofMinutes(1));
		OrderPaidEvent event = new OrderPaidEvent(42L, 10L, 3L, 7L, 4_500, LocalDateTime.of(2026, 7, 15, 10, 0));

		// when
		org.assertj.core.api.ThrowableAssert.ThrowingCallable aggregate = () -> service.aggregate(event);

		// then
		assertThatThrownBy(aggregate).isInstanceOf(IllegalStateException.class)
			.hasMessage("랭킹 Redis 재구성 중입니다.");
		assertThat(redisTemplate.opsForZSet().score(
			RedisRankingKey.dailyRanking(java.time.LocalDate.of(2026, 7, 15)), "7"
		)).isNull();
	}

	@Test
	void 처리_건수_키가_비정상이면_마커와_점수와_상태를_부분_반영하지_않는다() {
		// given
		OrderPaidEvent event = new OrderPaidEvent(42L, 10L, 3L, 7L, 4_500, LocalDateTime.of(2026, 7, 15, 10, 0));
		java.time.LocalDate date = java.time.LocalDate.of(2026, 7, 15);
		String countKey = RedisRankingKey.dailyProcessedOrderCount(date);
		String rankingKey = RedisRankingKey.dailyRanking(date);
		String statusKey = RedisRankingKey.dailyStatus(date);
		String processedKey = RedisRankingKey.processedEvent(event.eventId());
		redisTemplate.opsForValue().set(countKey, "1.5");

		// when
		assertThatThrownBy(() -> service.aggregate(event))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("랭킹 Redis 집계 키 형식이 올바르지 않습니다.");

		// then
		assertThat(redisTemplate.opsForValue().get(countKey)).isEqualTo("1.5");
		assertThat(redisTemplate.hasKey(processedKey)).isFalse();
		assertThat(redisTemplate.opsForZSet().score(rankingKey, "7")).isNull();
		assertThat(redisTemplate.opsForValue().get(statusKey)).isNull();
	}
}
