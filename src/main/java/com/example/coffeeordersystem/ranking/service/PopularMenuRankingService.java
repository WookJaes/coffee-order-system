package com.example.coffeeordersystem.ranking.service;

import com.example.coffeeordersystem.order.repository.OrderRepository;
import com.example.coffeeordersystem.order.repository.OrderEventRepository;
import com.example.coffeeordersystem.ranking.dto.DailyMenuOrderCount;
import com.example.coffeeordersystem.ranking.dto.PopularMenuRanking;
import com.example.coffeeordersystem.ranking.dto.RebuildOrderEvent;
import com.example.coffeeordersystem.ranking.redis.RedisRankingKey;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;

public class PopularMenuRankingService {

	private static final int RANKING_DAYS = 7;

	private final StringRedisTemplate redisTemplate;
	private final OrderRepository orderRepository;
	private final OrderEventRepository orderEventRepository;
	private final Duration keyTtl;
	private final Duration rebuildLockTtl;
	private final RedisScript<Long> releaseLockScript;
	private final Clock clock;

	public PopularMenuRankingService(
		StringRedisTemplate redisTemplate,
		OrderRepository orderRepository,
		OrderEventRepository orderEventRepository,
		Duration keyTtl,
		Duration rebuildLockTtl,
		RedisScript<Long> releaseLockScript,
		Clock clock
	) {
		this.redisTemplate = redisTemplate;
		this.orderRepository = orderRepository;
		this.orderEventRepository = orderEventRepository;
		this.keyTtl = keyTtl;
		this.rebuildLockTtl = rebuildLockTtl;
		this.releaseLockScript = releaseLockScript;
		this.clock = clock;
	}

	public List<PopularMenuRanking> getPopularMenuRankings() {
		LocalDate today = LocalDate.now(clock);
		Map<Long, Long> orderCounts = readRedisRankings(today);
		if (!isRankingComplete(today)) {
			orderCounts = recoverRankings(today);
		}
		return toTopRankings(orderCounts);
	}

	private Map<Long, Long> readRedisRankings(LocalDate today) {
		Map<Long, Long> orderCounts = new HashMap<>();
		ZSetOperations<String, String> zSetOperations = redisTemplate.opsForZSet();
		for (int offset = 0; offset < RANKING_DAYS; offset++) {
			Set<ZSetOperations.TypedTuple<String>> tuples = zSetOperations.rangeWithScores(
				RedisRankingKey.dailyRanking(today.minusDays(offset)), 0, -1
			);
			if (tuples == null) {
				continue;
			}
			for (ZSetOperations.TypedTuple<String> tuple : tuples) {
				if (tuple.getValue() != null && tuple.getScore() != null) {
					orderCounts.merge(Long.parseLong(tuple.getValue()), tuple.getScore().longValue(), Long::sum);
				}
			}
		}
		return orderCounts;
	}

	private Map<Long, Long> recoverRankings(LocalDate today) {
		LocalDateTime start = today.minusDays(RANKING_DAYS - 1L).atStartOfDay();
		LocalDateTime end = today.plusDays(1).atStartOfDay();
		String lockToken = UUID.randomUUID().toString();
		Map<Long, Long> totals = new HashMap<>();
		Boolean locked = redisTemplate.opsForValue().setIfAbsent(
			RedisRankingKey.rebuilding(), lockToken, rebuildLockTtl
		);
		if (!Boolean.TRUE.equals(locked)) {
			List<DailyMenuOrderCount> dailyCounts = orderRepository.findDailyPaidMenuOrderCounts(start, end);
			for (DailyMenuOrderCount dailyCount : dailyCounts) {
				totals.merge(dailyCount.menuId(), dailyCount.orderCount(), Long::sum);
			}
			return totals;
		}
		List<String> restoredMarkerKeys = new java.util.ArrayList<>();
		try {
			Map<Long, Long> currentRankings = readRedisRankings(today);
			if (isRankingComplete(today)) {
				return currentRankings;
			}
			List<DailyMenuOrderCount> dailyCounts = orderRepository.findDailyPaidMenuOrderCounts(start, end);
			List<RebuildOrderEvent> events = orderEventRepository.findPaidEventsForRankingRebuild(start, end);
			List<String> dailyKeys = dailyKeys(today);
			redisTemplate.delete(dailyKeys);
			for (RebuildOrderEvent event : events) {
				String markerKey = RedisRankingKey.processedEvent(event.eventId());
				Boolean markerCreated = redisTemplate.opsForValue().setIfAbsent(markerKey, "1", keyTtl);
				if (Boolean.TRUE.equals(markerCreated)) {
					restoredMarkerKeys.add(markerKey);
				}
			}
			for (DailyMenuOrderCount dailyCount : dailyCounts) {
				String rankingKey = RedisRankingKey.dailyRanking(toLocalDate(dailyCount.orderedDate()));
				redisTemplate.opsForZSet().add(
					rankingKey,
					dailyCount.menuId().toString(),
					dailyCount.orderCount()
				);
				redisTemplate.expire(rankingKey, keyTtl);
				totals.merge(dailyCount.menuId(), dailyCount.orderCount(), Long::sum);
			}
			for (int offset = 0; offset < RANKING_DAYS; offset++) {
				LocalDate date = today.minusDays(offset);
				String status = totals.isEmpty() ? "EMPTY" : hasDailyCount(dailyCounts, date) ? "DATA" : "EMPTY";
				redisTemplate.opsForValue().set(RedisRankingKey.dailyStatus(date), status, keyTtl);
			}
		} catch (RuntimeException exception) {
			redisTemplate.delete(restoredMarkerKeys);
			redisTemplate.delete(dailyKeys(today));
			throw exception;
		} finally {
			releaseRebuildLock(lockToken);
		}
		return totals;
	}

	private boolean isRankingComplete(LocalDate today) {
		for (int offset = 0; offset < RANKING_DAYS; offset++) {
			LocalDate date = today.minusDays(offset);
			String status = redisTemplate.opsForValue().get(RedisRankingKey.dailyStatus(date));
			Boolean rankingKeyExists = redisTemplate.hasKey(RedisRankingKey.dailyRanking(date));
			if ("DATA".equals(status) && Boolean.TRUE.equals(rankingKeyExists)) {
				continue;
			}
			if ("EMPTY".equals(status) && !Boolean.TRUE.equals(rankingKeyExists)) {
				continue;
			}
			return false;
		}
		return true;
	}

	private boolean hasDailyCount(List<DailyMenuOrderCount> dailyCounts, LocalDate date) {
		return dailyCounts.stream().anyMatch(dailyCount -> toLocalDate(dailyCount.orderedDate()).equals(date));
	}

	private List<String> dailyKeys(LocalDate today) {
		List<String> keys = new java.util.ArrayList<>();
		for (int offset = 0; offset < RANKING_DAYS; offset++) {
			LocalDate date = today.minusDays(offset);
			keys.add(RedisRankingKey.dailyRanking(date));
			keys.add(RedisRankingKey.dailyStatus(date));
		}
		return keys;
	}

	private void releaseRebuildLock(String lockToken) {
		redisTemplate.execute(releaseLockScript, List.of(RedisRankingKey.rebuilding()), lockToken);
	}

	private LocalDate toLocalDate(Object orderedDate) {
		if (orderedDate instanceof LocalDate localDate) {
			return localDate;
		}
		if (orderedDate instanceof java.sql.Date sqlDate) {
			return sqlDate.toLocalDate();
		}
		throw new IllegalStateException("주문 일자 집계 결과를 LocalDate로 변환할 수 없습니다.");
	}

	private List<PopularMenuRanking> toTopRankings(Map<Long, Long> orderCounts) {
		return orderCounts.entrySet().stream()
			.sorted(Map.Entry.<Long, Long>comparingByValue(Comparator.reverseOrder())
				.thenComparing(Map.Entry.comparingByKey()))
			.map(entry -> new PopularMenuRanking(entry.getKey(), entry.getValue()))
			.toList();
	}
}
