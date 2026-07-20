package com.example.coffeeordersystem.ranking.redis;

import com.example.coffeeordersystem.outbox.dto.OrderPaidEvent;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

public class RedisRankingAggregationService {

	private static final long ORDER_COUNT_INCREMENT = 1L;
	private static final ZoneId ORDER_TIME_ZONE = ZoneId.of("Asia/Seoul");
	private final StringRedisTemplate redisTemplate;
	private final Duration keyTtl;
	private final Clock clock;
	private final RedisScript<Long> processOnceScript;

	public RedisRankingAggregationService(
		StringRedisTemplate redisTemplate,
		Duration keyTtl,
		Clock clock,
		RedisScript<Long> processOnceScript
	) {
		this.redisTemplate = redisTemplate;
		this.keyTtl = keyTtl;
		this.clock = clock;
		this.processOnceScript = processOnceScript;
	}

	public boolean aggregate(OrderPaidEvent event) {
		LocalDate date = event.orderedAt().atZone(ORDER_TIME_ZONE).toLocalDate();
		Long result = redisTemplate.execute(
			processOnceScript,
			List.of(
				RedisRankingKey.processedEvent(event.eventId()),
				RedisRankingKey.dailyRanking(date),
				RedisRankingKey.dailyProcessedOrderCount(date),
				RedisRankingKey.rebuilding(),
				RedisRankingKey.dailyStatus(date)
			),
			Long.toString(keyTtl.toSeconds()),
			Long.toString(ORDER_COUNT_INCREMENT),
			event.menuId().toString()
		);
		if (Long.valueOf(-1L).equals(result)) {
			throw new RankingRebuildInProgressException();
		}
		if (Long.valueOf(-2L).equals(result)) {
			throw new IllegalStateException("랭킹 Redis 집계 키 형식이 올바르지 않습니다.");
		}
		return Long.valueOf(1L).equals(result);
	}
}
