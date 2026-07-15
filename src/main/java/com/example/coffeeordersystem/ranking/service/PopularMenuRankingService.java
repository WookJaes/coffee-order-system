package com.example.coffeeordersystem.ranking.service;

import com.example.coffeeordersystem.order.repository.OrderRepository;
import com.example.coffeeordersystem.ranking.dto.DailyMenuOrderCount;
import com.example.coffeeordersystem.ranking.dto.PopularMenuRanking;
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

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

public class PopularMenuRankingService {

	private static final int RANKING_DAYS = 7;

	private final StringRedisTemplate redisTemplate;
	private final OrderRepository orderRepository;
	private final Duration keyTtl;
	private final Clock clock;

	public PopularMenuRankingService(
		StringRedisTemplate redisTemplate,
		OrderRepository orderRepository,
		Duration keyTtl,
		Clock clock
	) {
		this.redisTemplate = redisTemplate;
		this.orderRepository = orderRepository;
		this.keyTtl = keyTtl;
		this.clock = clock;
	}

	public List<PopularMenuRanking> getPopularMenuRankings() {
		LocalDate today = LocalDate.now(clock);
		Map<Long, Long> orderCounts = readRedisRankings(today);
		if (orderCounts.isEmpty()) {
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
		List<DailyMenuOrderCount> dailyCounts = orderRepository.findDailyPaidMenuOrderCounts(start, end);
		Map<Long, Long> totals = new HashMap<>();
		for (DailyMenuOrderCount dailyCount : dailyCounts) {
			redisTemplate.opsForZSet().incrementScore(
				RedisRankingKey.dailyRanking(toLocalDate(dailyCount.orderedDate())),
				dailyCount.menuId().toString(),
				dailyCount.orderCount()
			);
			redisTemplate.expire(RedisRankingKey.dailyRanking(toLocalDate(dailyCount.orderedDate())), keyTtl);
			totals.merge(dailyCount.menuId(), dailyCount.orderCount(), Long::sum);
		}
		return totals;
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
