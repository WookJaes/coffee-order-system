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
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.dao.QueryTimeoutException;
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
		LedgerSnapshot ledger = buildLedgerSnapshot(today, orderRepository.findDailyPaidMenuOrderCounts(start, end));
		RedisRankingSnapshot redisSnapshot;
		try {
			redisSnapshot = readRedisSnapshot(today);
		} catch (RedisConnectionFailureException | RedisSystemException | QueryTimeoutException exception) {
			return toTopRankings(ledger.totals());
		}
		if (redisSnapshot.matches(today, ledger.dailyCounts(), ledger.dailyMenuCounts())) {
			return toTopRankings(redisSnapshot.orderCounts());
		}
		return toTopRankings(recoverRankings(today, start, end, ledger));
	}

	private LedgerSnapshot buildLedgerSnapshot(LocalDate today, List<DailyMenuOrderCount> dailyRows) {
		Map<LocalDate, Long> dailyCounts = new HashMap<>();
		Map<LocalDate, Map<Long, Long>> dailyMenuCounts = new HashMap<>();
		Map<Long, Long> totals = new HashMap<>();
		for (int offset = 0; offset < RANKING_DAYS; offset++) {
			LocalDate date = today.minusDays(offset);
			dailyCounts.put(date, 0L);
			dailyMenuCounts.put(date, new HashMap<>());
		}
		for (DailyMenuOrderCount dailyRow : dailyRows) {
			LocalDate date = toLocalDate(dailyRow.orderedDate());
			dailyCounts.merge(date, dailyRow.orderCount(), Long::sum);
			dailyMenuCounts.computeIfAbsent(date, ignored -> new HashMap<>())
				.merge(dailyRow.menuId(), dailyRow.orderCount(), Long::sum);
			totals.merge(dailyRow.menuId(), dailyRow.orderCount(), Long::sum);
		}
		return new LedgerSnapshot(dailyRows, dailyCounts, dailyMenuCounts, totals);
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
			return RedisRankingSnapshot.invalid();
		}

		String[] dailySnapshots = encoded.split(";", -1);
		if (dailySnapshots.length != RANKING_DAYS) {
			return RedisRankingSnapshot.invalid();
		}
		Map<LocalDate, RedisDailySnapshot> snapshots = new HashMap<>();
		Map<Long, Long> orderCounts = new HashMap<>();
		try {
			for (int offset = 0; offset < RANKING_DAYS; offset++) {
				LocalDate date = today.minusDays(offset);
				RedisDailySnapshot dailySnapshot = parseDailySnapshot(dailySnapshots[offset]);
				snapshots.put(date, dailySnapshot);
				dailySnapshot.orderCounts().forEach((menuId, count) -> orderCounts.merge(menuId, count, Long::sum));
			}
		} catch (InvalidRedisSnapshotException exception) {
			return RedisRankingSnapshot.invalid();
		}
		return new RedisRankingSnapshot(true, snapshots, orderCounts);
	}

	private RedisDailySnapshot parseDailySnapshot(String encoded) {
		String[] fields = encoded.split("\\|", -1);
		if (fields.length != 4) {
			throw new InvalidRedisSnapshotException("랭킹 Redis 일자 snapshot 형식이 올바르지 않습니다.");
		}
		Map<Long, Long> orderCounts = new HashMap<>();
		try {
			if (!fields[3].isEmpty()) {
				for (String entry : fields[3].split(",", -1)) {
					String[] memberAndScore = entry.split("=", -1);
					if (memberAndScore.length != 2) {
						throw new InvalidRedisSnapshotException("랭킹 Redis ZSET snapshot 형식이 올바르지 않습니다.");
					}
					Long menuId = Long.valueOf(memberAndScore[0]);
					Long count = parseIntegralCount(memberAndScore[1]);
					if (orderCounts.put(menuId, count) != null) {
						throw new InvalidRedisSnapshotException("랭킹 Redis ZSET snapshot에 중복 메뉴가 있습니다.");
					}
				}
			}
		} catch (NumberFormatException exception) {
			throw new InvalidRedisSnapshotException("랭킹 Redis ZSET snapshot 숫자 형식이 올바르지 않습니다.", exception);
		}
		Long processedCount = fields[1].isEmpty() ? null : parseIntegralCount(fields[1]);
		return new RedisDailySnapshot(
			fields[0].isEmpty() ? null : fields[0],
			processedCount,
			"1".equals(fields[2]),
			orderCounts
		);
	}

	private Long parseIntegralCount(String value) {
		double parsed;
		try {
			parsed = Double.parseDouble(value);
		} catch (NumberFormatException exception) {
			throw new InvalidRedisSnapshotException("랭킹 Redis count 숫자 형식이 올바르지 않습니다.", exception);
		}
		if (!Double.isFinite(parsed) || parsed < 0 || parsed != Math.rint(parsed) || parsed > Long.MAX_VALUE) {
			throw new InvalidRedisSnapshotException("랭킹 Redis count는 음이 아닌 정수여야 합니다.");
		}
		return (long) parsed;
	}

	private Map<Long, Long> recoverRankings(
		LocalDate today,
		LocalDateTime start,
		LocalDateTime end,
		LedgerSnapshot ledger
	) {
		String lockToken = UUID.randomUUID().toString();
		Boolean locked;
		try {
			locked = redisTemplate.opsForValue().setIfAbsent(
				RedisRankingKey.rebuilding(), lockToken, rebuildLockTtl
			);
		} catch (RedisConnectionFailureException | RedisSystemException | QueryTimeoutException exception) {
			return ledger.totals();
		}
		if (!Boolean.TRUE.equals(locked)) {
			return ledger.totals();
		}

		List<String> restoredMarkerKeys = new ArrayList<>();
		AtomicBoolean ownershipLost = new AtomicBoolean(false);
		AtomicBoolean redisAccessFailed = new AtomicBoolean(false);
		ScheduledFuture<?> leaseRenewal = startLeaseRenewal(lockToken, ownershipLost, redisAccessFailed);
		try {
			RedisRankingSnapshot currentRankings = readRedisSnapshot(today);
			if (currentRankings.matches(today, ledger.dailyCounts(), ledger.dailyMenuCounts())) {
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
		} catch (RedisConnectionFailureException | RedisSystemException | QueryTimeoutException exception) {
			redisAccessFailed.set(true);
			return ledger.totals();
		} catch (RuntimeException exception) {
			if (redisAccessFailed.get()) {
				return ledger.totals();
			}
			try {
				cleanupFailedRebuild(lockToken, today, restoredMarkerKeys);
			} catch (RedisConnectionFailureException | RedisSystemException | QueryTimeoutException redisException) {
				redisAccessFailed.set(true);
				return ledger.totals();
			}
			if (!isOwnershipLost(exception)) {
				throw exception;
			}
			return ledger.totals();
		} finally {
			leaseRenewal.cancel(false);
			if (!redisAccessFailed.get()) {
				try {
					releaseRebuildLock(lockToken);
				} catch (RedisConnectionFailureException | RedisSystemException | QueryTimeoutException ignored) {
					// Redis 장애 중에는 DB 원장 결과를 반환하며 추가 Redis 호출을 하지 않는다.
				}
			}
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

	private ScheduledFuture<?> startLeaseRenewal(
		String lockToken,
		AtomicBoolean ownershipLost,
		AtomicBoolean redisAccessFailed
	) {
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
			} catch (RedisConnectionFailureException | RedisSystemException | QueryTimeoutException exception) {
				redisAccessFailed.set(true);
				ownershipLost.set(true);
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
		Map<LocalDate, Map<Long, Long>> dailyMenuCounts,
		Map<Long, Long> totals
	) {
	}

	private record RedisRankingSnapshot(
		boolean valid,
		Map<LocalDate, RedisDailySnapshot> dailySnapshots,
		Map<Long, Long> orderCounts
	) {

		private static RedisRankingSnapshot invalid() {
			return new RedisRankingSnapshot(false, Map.of(), Map.of());
		}

		private boolean matches(
			LocalDate today,
			Map<LocalDate, Long> expectedDailyCounts,
			Map<LocalDate, Map<Long, Long>> expectedDailyMenuCounts
		) {
			if (!valid) {
				return false;
			}
			for (int offset = 0; offset < RANKING_DAYS; offset++) {
				LocalDate date = today.minusDays(offset);
				RedisDailySnapshot snapshot = dailySnapshots.get(date);
				long expected = expectedDailyCounts.getOrDefault(date, 0L);
				Map<Long, Long> expectedMenuCounts = expectedDailyMenuCounts.getOrDefault(date, Map.of());
				if (snapshot == null || snapshot.processedCount() == null || snapshot.processedCount() != expected) {
					return false;
				}
				if (!snapshot.orderCounts().equals(expectedMenuCounts)) {
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

	private static final class InvalidRedisSnapshotException extends RuntimeException {

		private InvalidRedisSnapshotException(String message) {
			super(message);
		}

		private InvalidRedisSnapshotException(String message, Throwable cause) {
			super(message, cause);
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
