package com.example.coffeeordersystem.ranking.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.coffeeordersystem.outbox.dto.OrderPaidEvent;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
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
	void 동일_eventId를_두번_처리해도_실제_Redis_ZSET_점수는_한번만_증가하고_TTL이_설정된다() {
		// given
		OrderPaidEvent event = new OrderPaidEvent(42L, 10L, 3L, 7L, 4_500);
		String rankingKey = RedisRankingKey.dailyRanking(java.time.LocalDate.of(2026, 7, 15));
		String processedKey = RedisRankingKey.processedEvent(event.eventId());

		// when
		boolean firstAggregated = service.aggregate(event);
		boolean duplicateAggregated = service.aggregate(event);

		// then
		assertThat(firstAggregated).isTrue();
		assertThat(duplicateAggregated).isFalse();
		assertThat(redisTemplate.opsForZSet().score(rankingKey, "7")).isEqualTo(1.0);
		assertThat(redisTemplate.getExpire(rankingKey)).isPositive().isLessThanOrEqualTo(KEY_TTL.getSeconds());
		assertThat(redisTemplate.getExpire(processedKey)).isPositive().isLessThanOrEqualTo(KEY_TTL.getSeconds());
	}

	@Test
	void 실제_Redis_재구성_잠금이_있으면_집계를_중단한다() {
		// given
		redisTemplate.opsForValue().set(RedisRankingKey.rebuilding(), "1", Duration.ofMinutes(1));
		OrderPaidEvent event = new OrderPaidEvent(42L, 10L, 3L, 7L, 4_500);

		// when
		org.assertj.core.api.ThrowableAssert.ThrowingCallable aggregate = () -> service.aggregate(event);

		// then
		assertThatThrownBy(aggregate).isInstanceOf(IllegalStateException.class)
			.hasMessage("랭킹 Redis 재구성 중입니다.");
		assertThat(redisTemplate.opsForZSet().score(
			RedisRankingKey.dailyRanking(java.time.LocalDate.of(2026, 7, 15)), "7"
		)).isNull();
	}
}
