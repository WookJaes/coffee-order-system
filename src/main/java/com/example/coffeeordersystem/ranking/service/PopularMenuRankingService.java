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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

public class PopularMenuRankingService {

	private static final int RANKING_DAYS = 7;

	private final StringRedisTemplate redisTemplate;
	private final OrderRepository orderRepository;
	private final OrderEventRepository orderEventRepository;
	private final Duration keyTtl;
	private final Duration rebuildLockTtl;
	private final Duration rebuildLockRenewInterval;
	private final RedisScript<Long> releaseLockScript;
	private final RedisScript<Long> renewLockScript;
	private final RedisScript<Long> cleanupRebuildMarkerScript;
	private final RedisScript<Long> cleanupRebuildScript;
	private final RedisScript<Long> rebuildWriteScript;
	private final ScheduledExecutorService leaseScheduler;
	private final Clock clock;

	public PopularMenuRankingService(
		StringRedisTemplate redisTemplate,
		OrderRepository orderRepository,
		OrderEventRepository orderEventRepository,
		Duration keyTtl,
		Duration rebuildLockTtl,
		Duration rebuildLockRenewInterval,
		RedisScript<Long> releaseLockScript,
		RedisScript<Long> renewLockScript,
		RedisScript<Long> cleanupRebuildMarkerScript,
		RedisScript<Long> cleanupRebuildScript,
		RedisScript<Long> rebuildWriteScript,
		ScheduledExecutorService leaseScheduler,
		Clock clock
	) {
		this.redisTemplate = redisTemplate;
		this.orderRepository = orderRepository;
		this.orderEventRepository = orderEventRepository;
		this.keyTtl = keyTtl;
		this.rebuildLockTtl = rebuildLockTtl;
		this.rebuildLockRenewInterval = rebuildLockRenewInterval;
		this.releaseLockScript = releaseLockScript;
		this.renewLockScript = renewLockScript;
		this.cleanupRebuildMarkerScript = cleanupRebuildMarkerScript;
		this.cleanupRebuildScript = cleanupRebuildScript;
		this.rebuildWriteScript = rebuildWriteScript;
		this.leaseScheduler = leaseScheduler;
		this.clock = clock;
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, propagation = Propagation.REQUIRES_NEW)
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
		AtomicBoolean ownershipLost = new AtomicBoolean(false);
		ScheduledFuture<?> leaseRenewal = startLeaseRenewal(lockToken, ownershipLost);
		try {
			Map<Long, Long> currentRankings = readRedisRankings(today);
			if (isRankingComplete(today)) {
				return currentRankings;
			}
			List<DailyMenuOrderCount> dailyCounts = orderRepository.findDailyPaidMenuOrderCounts(start, end);
			assertLockOwnership(ownershipLost);
			List<RebuildOrderEvent> events = orderEventRepository.findPaidEventsForRankingRebuild(start, end);
			List<String> dailyKeys = dailyKeys(today);
			writeRebuild(lockToken, withLockKey(dailyKeys), "CLEAR");
			for (RebuildOrderEvent event : events) {
				assertLockOwnership(ownershipLost);
				String markerKey = RedisRankingKey.processedEvent(event.eventId());
				Long markerCreated = writeRebuild(lockToken, List.of(RedisRankingKey.rebuilding(), markerKey), "MARKER");
				if (Long.valueOf(1L).equals(markerCreated)) {
					restoredMarkerKeys.add(markerKey);
				}
			}
			for (DailyMenuOrderCount dailyCount : dailyCounts) {
				assertLockOwnership(ownershipLost);
				String rankingKey = RedisRankingKey.dailyRanking(toLocalDate(dailyCount.orderedDate()));
				writeRebuild(lockToken, List.of(RedisRankingKey.rebuilding(), rankingKey), "RANKING",
					dailyCount.orderCount().toString(), dailyCount.menuId().toString());
				totals.merge(dailyCount.menuId(), dailyCount.orderCount(), Long::sum);
			}
			for (int offset = 0; offset < RANKING_DAYS; offset++) {
				assertLockOwnership(ownershipLost);
				LocalDate date = today.minusDays(offset);
				String status = totals.isEmpty() ? "EMPTY" : hasDailyCount(dailyCounts, date) ? "DATA" : "EMPTY";
				writeRebuild(lockToken, List.of(RedisRankingKey.rebuilding(), RedisRankingKey.dailyStatus(date)), "STATUS", status);
			}
		} catch (RuntimeException exception) {
			cleanupFailedRebuild(lockToken, today, restoredMarkerKeys);
			throw exception;
		} finally {
			leaseRenewal.cancel(false);
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

	private ScheduledFuture<?> startLeaseRenewal(String lockToken, AtomicBoolean ownershipLost) {
		long renewalSeconds = rebuildLockRenewInterval.toSeconds();
		return leaseScheduler.scheduleAtFixedRate(() -> {
			try {
				Long renewed = redisTemplate.execute(
					renewLockScript,
					List.of(RedisRankingKey.rebuilding()),
					lockToken,
					Long.toString(rebuildLockTtl.toSeconds())
				);
				if (!Long.valueOf(1L).equals(renewed)) {
					ownershipLost.set(true);
				}
			} catch (RuntimeException exception) {
				ownershipLost.set(true);
			}
		}, renewalSeconds, renewalSeconds, TimeUnit.SECONDS);
	}

	private void assertLockOwnership(AtomicBoolean ownershipLost) {
		if (ownershipLost.get()) {
			throw new IllegalStateException("랭킹 Redis 재구성 잠금 소유권을 잃었습니다.");
		}
	}

	private Long writeRebuild(String lockToken, List<String> keys, String operation, String... values) {
		Object[] arguments = new Object[3 + values.length];
		arguments[0] = lockToken;
		arguments[1] = operation;
		arguments[2] = Long.toString(keyTtl.toSeconds());
		System.arraycopy(values, 0, arguments, 3, values.length);
		Long result = redisTemplate.execute(rebuildWriteScript, keys, arguments);
		if (Long.valueOf(-1L).equals(result)) {
			throw new IllegalStateException("랭킹 Redis 재구성 잠금 소유권을 잃었습니다.");
		}
		return result;
	}

	private void cleanupFailedRebuild(String lockToken, LocalDate today, List<String> restoredMarkerKeys) {
		redisTemplate.execute(
			cleanupRebuildScript,
			withLockKey(dailyKeys(today)),
			lockToken
		);
		for (String markerKey : restoredMarkerKeys) {
			redisTemplate.execute(cleanupRebuildMarkerScript, List.of(RedisRankingKey.rebuilding(), markerKey), lockToken);
		}
	}

	private List<String> withLockKey(List<String> keys) {
		List<String> keysWithLock = new java.util.ArrayList<>();
		keysWithLock.add(RedisRankingKey.rebuilding());
		keysWithLock.addAll(keys);
		return keysWithLock;
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
