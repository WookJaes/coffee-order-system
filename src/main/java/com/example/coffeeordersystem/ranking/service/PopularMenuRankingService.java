package com.example.coffeeordersystem.ranking.service;

import com.example.coffeeordersystem.order.repository.OrderEventRepository;
import com.example.coffeeordersystem.order.repository.OrderRepository;
import com.example.coffeeordersystem.ranking.dto.DailyMenuOrderCount;
import com.example.coffeeordersystem.ranking.dto.PopularMenuRanking;
import com.example.coffeeordersystem.ranking.dto.RebuildOrderEvent;
import com.example.coffeeordersystem.ranking.redis.RedisRankingKey;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

public class PopularMenuRankingService {

	private static final int RANKING_DAYS = 7;
	private static final String DATA_STATUS = "DATA";
	private static final String EMPTY_STATUS = "EMPTY";
	private static final String OWNERSHIP_LOST_MESSAGE = "랭킹 Redis 재구성 잠금 소유권을 잃었습니다.";

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
	private final RedisScript<String> readSnapshotScript;
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
		RedisScript<String> readSnapshotScript,
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
		this.readSnapshotScript = readSnapshotScript;
		this.leaseScheduler = leaseScheduler;
		this.clock = clock;
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, propagation = Propagation.REQUIRES_NEW)
	public List<PopularMenuRanking> getPopularMenuRankings() {
		LocalDate today = LocalDate.now(clock);
		LocalDateTime start = today.minusDays(RANKING_DAYS - 1L).atStartOfDay();
		LocalDateTime end = today.plusDays(1).atStartOfDay();
		RedisRankingSnapshot redisSnapshot = readRedisSnapshot(today);
		LedgerSnapshot ledger = buildLedgerSnapshot(today, orderRepository.findDailyPaidMenuOrderCounts(start, end));
		if (redisSnapshot.matches(today, ledger.dailyCounts())) {
			return toTopRankings(redisSnapshot.orderCounts());
		}
		return toTopRankings(recoverRankings(today, start, end, ledger));
	}

	private LedgerSnapshot buildLedgerSnapshot(LocalDate today, List<DailyMenuOrderCount> dailyRows) {
		Map<LocalDate, Long> dailyCounts = new HashMap<>();
		Map<Long, Long> totals = new HashMap<>();
		for (int offset = 0; offset < RANKING_DAYS; offset++) {
			dailyCounts.put(today.minusDays(offset), 0L);
		}
		for (DailyMenuOrderCount dailyRow : dailyRows) {
			LocalDate date = toLocalDate(dailyRow.orderedDate());
			dailyCounts.merge(date, dailyRow.orderCount(), Long::sum);
			totals.merge(dailyRow.menuId(), dailyRow.orderCount(), Long::sum);
		}
		return new LedgerSnapshot(dailyRows, dailyCounts, totals);
	}

	private RedisRankingSnapshot readRedisSnapshot(LocalDate today) {
		List<String> keys = new ArrayList<>();
		for (int offset = 0; offset < RANKING_DAYS; offset++) {
			LocalDate date = today.minusDays(offset);
			keys.add(RedisRankingKey.dailyRanking(date));
			keys.add(RedisRankingKey.dailyStatus(date));
			keys.add(RedisRankingKey.dailyProcessedOrderCount(date));
		}
		String encoded = redisTemplate.execute(readSnapshotScript, keys, new Object[0]);
		if (encoded == null) {
			throw new IllegalStateException("랭킹 Redis snapshot을 읽을 수 없습니다.");
		}

		String[] dailySnapshots = encoded.split(";", -1);
		if (dailySnapshots.length != RANKING_DAYS) {
			throw new IllegalStateException("랭킹 Redis snapshot 형식이 올바르지 않습니다.");
		}
		Map<LocalDate, RedisDailySnapshot> snapshots = new HashMap<>();
		Map<Long, Long> orderCounts = new HashMap<>();
		for (int offset = 0; offset < RANKING_DAYS; offset++) {
			LocalDate date = today.minusDays(offset);
			RedisDailySnapshot dailySnapshot = parseDailySnapshot(dailySnapshots[offset]);
			snapshots.put(date, dailySnapshot);
			dailySnapshot.orderCounts().forEach((menuId, count) -> orderCounts.merge(menuId, count, Long::sum));
		}
		return new RedisRankingSnapshot(snapshots, orderCounts);
	}

	private RedisDailySnapshot parseDailySnapshot(String encoded) {
		String[] fields = encoded.split("\\|", -1);
		if (fields.length != 4) {
			throw new IllegalStateException("랭킹 Redis 일자 snapshot 형식이 올바르지 않습니다.");
		}
		Map<Long, Long> orderCounts = new HashMap<>();
		if (!fields[3].isEmpty()) {
			for (String entry : fields[3].split(",", -1)) {
				String[] memberAndScore = entry.split("=", -1);
				if (memberAndScore.length != 2) {
					throw new IllegalStateException("랭킹 Redis ZSET snapshot 형식이 올바르지 않습니다.");
				}
				orderCounts.put(Long.parseLong(memberAndScore[0]), Double.valueOf(memberAndScore[1]).longValue());
			}
		}
		Long processedCount = fields[1].isEmpty() ? null : Long.valueOf(fields[1]);
		return new RedisDailySnapshot(
			fields[0].isEmpty() ? null : fields[0],
			processedCount,
			"1".equals(fields[2]),
			orderCounts
		);
	}

	private Map<Long, Long> recoverRankings(
		LocalDate today,
		LocalDateTime start,
		LocalDateTime end,
		LedgerSnapshot ledger
	) {
		String lockToken = UUID.randomUUID().toString();
		Boolean locked = redisTemplate.opsForValue().setIfAbsent(
			RedisRankingKey.rebuilding(), lockToken, rebuildLockTtl
		);
		if (!Boolean.TRUE.equals(locked)) {
			return ledger.totals();
		}

		List<String> restoredMarkerKeys = new ArrayList<>();
		AtomicBoolean ownershipLost = new AtomicBoolean(false);
		ScheduledFuture<?> leaseRenewal = startLeaseRenewal(lockToken, ownershipLost);
		try {
			RedisRankingSnapshot currentRankings = readRedisSnapshot(today);
			if (currentRankings.matches(today, ledger.dailyCounts())) {
				return currentRankings.orderCounts();
			}

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
			for (DailyMenuOrderCount dailyCount : ledger.dailyRows()) {
				assertLockOwnership(ownershipLost);
				String rankingKey = RedisRankingKey.dailyRanking(toLocalDate(dailyCount.orderedDate()));
				writeRebuild(lockToken, List.of(RedisRankingKey.rebuilding(), rankingKey), "RANKING",
					dailyCount.orderCount().toString(), dailyCount.menuId().toString());
			}
			for (int offset = 0; offset < RANKING_DAYS; offset++) {
				assertLockOwnership(ownershipLost);
				LocalDate date = today.minusDays(offset);
				Long dailyCount = ledger.dailyCounts().getOrDefault(date, 0L);
				writeRebuild(lockToken, List.of(RedisRankingKey.rebuilding(), RedisRankingKey.dailyProcessedOrderCount(date)), "COUNT",
					dailyCount.toString());
				String status = dailyCount > 0L ? DATA_STATUS : EMPTY_STATUS;
				writeRebuild(lockToken, List.of(RedisRankingKey.rebuilding(), RedisRankingKey.dailyStatus(date)), "STATUS", status);
			}
		} catch (RuntimeException exception) {
			cleanupFailedRebuild(lockToken, today, restoredMarkerKeys);
			if (!isOwnershipLost(exception)) {
				throw exception;
			}
			return ledger.totals();
		} finally {
			leaseRenewal.cancel(false);
			releaseRebuildLock(lockToken);
		}
		return ledger.totals();
	}

	private boolean isOwnershipLost(RuntimeException exception) {
		return exception instanceof IllegalStateException && OWNERSHIP_LOST_MESSAGE.equals(exception.getMessage());
	}

	private List<String> dailyKeys(LocalDate today) {
		List<String> keys = new ArrayList<>();
		for (int offset = 0; offset < RANKING_DAYS; offset++) {
			LocalDate date = today.minusDays(offset);
			keys.add(RedisRankingKey.dailyRanking(date));
			keys.add(RedisRankingKey.dailyProcessedOrderCount(date));
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
			throw new IllegalStateException(OWNERSHIP_LOST_MESSAGE);
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
			throw new IllegalStateException(OWNERSHIP_LOST_MESSAGE);
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
		List<String> keysWithLock = new ArrayList<>();
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

	private record LedgerSnapshot(
		List<DailyMenuOrderCount> dailyRows,
		Map<LocalDate, Long> dailyCounts,
		Map<Long, Long> totals
	) {
	}

	private record RedisRankingSnapshot(
		Map<LocalDate, RedisDailySnapshot> dailySnapshots,
		Map<Long, Long> orderCounts
	) {

		private boolean matches(LocalDate today, Map<LocalDate, Long> expectedDailyCounts) {
			for (int offset = 0; offset < RANKING_DAYS; offset++) {
				LocalDate date = today.minusDays(offset);
				RedisDailySnapshot snapshot = dailySnapshots.get(date);
				long expected = expectedDailyCounts.getOrDefault(date, 0L);
				if (snapshot == null || snapshot.processedCount() == null || snapshot.processedCount() != expected) {
					return false;
				}
				long zsetTotal = snapshot.orderCounts().values().stream().mapToLong(Long::longValue).sum();
				if (zsetTotal != expected) {
					return false;
				}
				if (expected > 0L && (!DATA_STATUS.equals(snapshot.status()) || !snapshot.rankingExists())) {
					return false;
				}
				if (expected == 0L && (!EMPTY_STATUS.equals(snapshot.status()) || snapshot.rankingExists() || !snapshot.orderCounts().isEmpty())) {
					return false;
				}
			}
			return true;
		}
	}

	private record RedisDailySnapshot(
		String status,
		Long processedCount,
		boolean rankingExists,
		Map<Long, Long> orderCounts
	) {
	}
}
