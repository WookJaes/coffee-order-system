package com.example.coffeeordersystem.ranking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.example.coffeeordersystem.order.repository.OrderRepository;
import com.example.coffeeordersystem.ranking.dto.DailyMenuOrderCount;
import com.example.coffeeordersystem.ranking.dto.PopularMenuRanking;
import com.example.coffeeordersystem.ranking.redis.RedisRankingKey;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
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
	private StringRedisTemplate redisTemplate;
	private OrderRepository orderRepository;
	private PopularMenuRankingService service;

	@BeforeEach
	void setUp() {
		connectionFactory = new LettuceConnectionFactory(redis.getHost(), redis.getFirstMappedPort());
		connectionFactory.afterPropertiesSet();
		redisTemplate = new StringRedisTemplate(connectionFactory);
		redisTemplate.afterPropertiesSet();
		redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
		orderRepository = org.mockito.Mockito.mock(OrderRepository.class);
		service = new PopularMenuRankingService(redisTemplate, orderRepository, Duration.ofDays(8),
			Clock.fixed(Instant.parse("2026-07-15T01:00:00Z"), ZoneId.of("Asia/Seoul")));
	}

	@AfterAll
	static void tearDown() {
		if (connectionFactory != null) {
			connectionFactory.destroy();
		}
	}

	@Test
	void 실제_Redis에서_7일_점수를_합산한다() {
		// given
		redisTemplate.opsForZSet().incrementScore(RedisRankingKey.dailyRanking(LocalDate.of(2026, 7, 15)), "8", 2);
		redisTemplate.opsForZSet().incrementScore(RedisRankingKey.dailyRanking(LocalDate.of(2026, 7, 14)), "8", 3);
		redisTemplate.opsForZSet().incrementScore(RedisRankingKey.dailyRanking(LocalDate.of(2026, 7, 14)), "2", 5);

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
}
