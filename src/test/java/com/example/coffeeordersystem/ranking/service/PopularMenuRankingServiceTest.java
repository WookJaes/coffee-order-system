package com.example.coffeeordersystem.ranking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.coffeeordersystem.order.repository.OrderRepository;
import com.example.coffeeordersystem.order.repository.OrderEventRepository;
import com.example.coffeeordersystem.ranking.dto.DailyMenuOrderCount;
import com.example.coffeeordersystem.ranking.dto.PopularMenuRanking;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

class PopularMenuRankingServiceTest {

	private final StringRedisTemplate redisTemplate = org.mockito.Mockito.mock(StringRedisTemplate.class);
	private final ZSetOperations<String, String> zSetOperations = org.mockito.Mockito.mock(ZSetOperations.class);
	private final org.springframework.data.redis.core.ValueOperations<String, String> valueOperations = org.mockito.Mockito.mock(org.springframework.data.redis.core.ValueOperations.class);
	private final OrderRepository orderRepository = org.mockito.Mockito.mock(OrderRepository.class);
	private final OrderEventRepository orderEventRepository = org.mockito.Mockito.mock(OrderEventRepository.class);
	private final ScheduledExecutorService leaseScheduler = org.mockito.Mockito.mock(ScheduledExecutorService.class);
	@SuppressWarnings("unchecked")
	private final ScheduledFuture<?> defaultLeaseFuture = org.mockito.Mockito.mock(ScheduledFuture.class);
	private final Clock clock = Clock.fixed(Instant.parse("2026-07-15T01:00:00Z"), ZoneId.of("Asia/Seoul"));
	private final org.springframework.data.redis.core.script.DefaultRedisScript<String> readSnapshotScript =
		new org.springframework.data.redis.core.script.DefaultRedisScript<>("return ''", String.class);
	private final PopularMenuRankingService service = new PopularMenuRankingService(
		redisTemplate,
		orderRepository,
		orderEventRepository,
		Duration.ofDays(8),
		Duration.ofMinutes(1),
		Duration.ofSeconds(20),
		new org.springframework.data.redis.core.script.DefaultRedisScript<>("return 1", Long.class),
		new org.springframework.data.redis.core.script.DefaultRedisScript<>("return 1", Long.class),
		new org.springframework.data.redis.core.script.DefaultRedisScript<>("return 1", Long.class),
		new org.springframework.data.redis.core.script.DefaultRedisScript<>("return 1", Long.class),
		new org.springframework.data.redis.core.script.DefaultRedisScript<>("return 1", Long.class),
		readSnapshotScript,
		leaseScheduler,
		clock
	);

	@BeforeEach
	void setUpLeaseScheduler() {
		org.mockito.Mockito.doReturn(defaultLeaseFuture).when(leaseScheduler)
			.scheduleAtFixedRate(org.mockito.ArgumentMatchers.any(Runnable.class), org.mockito.ArgumentMatchers.anyLong(),
				org.mockito.ArgumentMatchers.anyLong(), any(TimeUnit.class));
		when(redisTemplate.execute(
			org.mockito.ArgumentMatchers.same(readSnapshotScript),
			org.mockito.ArgumentMatchers.<String>anyList(),
			org.mockito.ArgumentMatchers.any(Object[].class)
		)).thenReturn(emptyRedisSnapshot());
	}

	@Test
	void 요청일을_포함한_7일_ZSET_점수를_합산해_Top3를_반환한다() {
		// given
		when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(valueOperations.get(org.mockito.ArgumentMatchers.anyString())).thenReturn("DATA");
		when(redisTemplate.hasKey(org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
		when(redisTemplate.opsForValue().setIfAbsent(org.mockito.ArgumentMatchers.anyString(), eq("1"), any(Duration.class))).thenReturn(true);
		when(orderEventRepository.findPaidEventsForRankingRebuild(any(), any())).thenReturn(List.of());
		when(zSetOperations.rangeWithScores(any(), eq(0L), eq(-1L)))
			.thenReturn(tuples("1", 2, "2", 1))
			.thenReturn(tuples("1", 3, "3", 5))
			.thenReturn(Set.of())
			.thenReturn(Set.of())
			.thenReturn(Set.of())
			.thenReturn(Set.of())
			.thenReturn(Set.of());
		when(redisTemplate.execute(
			org.mockito.ArgumentMatchers.same(readSnapshotScript),
			org.mockito.ArgumentMatchers.<String>anyList(),
			org.mockito.ArgumentMatchers.any(Object[].class)
		)).thenReturn(redisSnapshot(
			"DATA|8|1|1=3,3=5",
			"DATA|3|1|1=2,2=1",
			"EMPTY|0|0|", "EMPTY|0|0|", "EMPTY|0|0|", "EMPTY|0|0|", "EMPTY|0|0|"
		));
		when(orderRepository.findDailyPaidMenuOrderCounts(any(), any())).thenReturn(List.of(
			new DailyMenuOrderCount(LocalDate.of(2026, 7, 15), 1L, 3L),
			new DailyMenuOrderCount(LocalDate.of(2026, 7, 15), 3L, 5L),
			new DailyMenuOrderCount(LocalDate.of(2026, 7, 14), 1L, 2L),
			new DailyMenuOrderCount(LocalDate.of(2026, 7, 14), 2L, 1L)
		));

		// when
		List<PopularMenuRanking> rankings = service.getPopularMenuRankings();

		// then
		assertThat(rankings).containsExactly(
			new PopularMenuRanking(1L, 5L),
			new PopularMenuRanking(3L, 5L),
			new PopularMenuRanking(2L, 1L)
		);
		ArgumentCaptor<List<String>> snapshotKeys = listCaptor();
		verify(redisTemplate).execute(org.mockito.ArgumentMatchers.same(readSnapshotScript), snapshotKeys.capture(), org.mockito.ArgumentMatchers.any(Object[].class));
		assertThat(snapshotKeys.getValue()).containsExactly(
			"coffee:ranking:2026-07-15", "coffee:ranking:status:2026-07-15", "coffee:ranking:count:2026-07-15",
			"coffee:ranking:2026-07-14", "coffee:ranking:status:2026-07-14", "coffee:ranking:count:2026-07-14",
			"coffee:ranking:2026-07-13", "coffee:ranking:status:2026-07-13", "coffee:ranking:count:2026-07-13",
			"coffee:ranking:2026-07-12", "coffee:ranking:status:2026-07-12", "coffee:ranking:count:2026-07-12",
			"coffee:ranking:2026-07-11", "coffee:ranking:status:2026-07-11", "coffee:ranking:count:2026-07-11",
			"coffee:ranking:2026-07-10", "coffee:ranking:status:2026-07-10", "coffee:ranking:count:2026-07-10",
			"coffee:ranking:2026-07-09", "coffee:ranking:status:2026-07-09", "coffee:ranking:count:2026-07-09"
		);
		ArgumentCaptor<LocalDateTime> start = ArgumentCaptor.forClass(LocalDateTime.class);
		ArgumentCaptor<LocalDateTime> end = ArgumentCaptor.forClass(LocalDateTime.class);
		verify(orderRepository).findDailyPaidMenuOrderCounts(start.capture(), end.capture());
		assertThat(start.getValue()).isEqualTo(LocalDateTime.of(2026, 7, 9, 0, 0));
		assertThat(end.getValue()).isEqualTo(LocalDateTime.of(2026, 7, 16, 0, 0));
		verify(valueOperations, org.mockito.Mockito.never()).setIfAbsent(any(), any(), any(Duration.class));
	}

	@Test
	void 일자별_총합이_같아도_메뉴별_Redis_점수가_원장과_다르면_DB_결과를_반환한다() {
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(valueOperations.setIfAbsent(any(), any(), any(Duration.class))).thenReturn(false);
		when(orderRepository.findDailyPaidMenuOrderCounts(any(), any())).thenReturn(List.of(
			new DailyMenuOrderCount(LocalDate.of(2026, 7, 15), 1L, 2L),
			new DailyMenuOrderCount(LocalDate.of(2026, 7, 15), 2L, 1L)
		));
		stubReadSnapshot("DATA|3|1|1=1,2=2", "|||", "|||", "|||", "|||", "|||", "|||");

		assertThat(service.getPopularMenuRankings()).containsExactly(
			new PopularMenuRanking(1L, 2L),
			new PopularMenuRanking(2L, 1L)
		);
	}

	@Test
	void malformed_Redis_snapshot은_예외를_내지_않고_DB_결과로_전환한다() {
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(valueOperations.setIfAbsent(any(), any(), any(Duration.class))).thenReturn(false);
		when(orderRepository.findDailyPaidMenuOrderCounts(any(), any())).thenReturn(List.of(
			new DailyMenuOrderCount(LocalDate.of(2026, 7, 15), 7L, 2L)
		));
		stubReadSnapshot("DATA|1|1|7=1.5", "|||", "|||", "|||", "|||", "|||", "|||");

		assertThat(service.getPopularMenuRankings())
			.containsExactly(new PopularMenuRanking(7L, 2L));
	}

	@Test
	void 숫자가_아닌_Redis_processed_count도_DB_결과로_전환한다() {
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(valueOperations.setIfAbsent(any(), any(), any(Duration.class))).thenReturn(false);
		when(orderRepository.findDailyPaidMenuOrderCounts(any(), any())).thenReturn(List.of(
			new DailyMenuOrderCount(LocalDate.of(2026, 7, 15), 7L, 2L)
		));
		stubReadSnapshot("DATA|not-a-count|1|7=1", "|||", "|||", "|||", "|||", "|||", "|||");

		assertThat(service.getPopularMenuRankings())
			.containsExactly(new PopularMenuRanking(7L, 2L));
	}

	@Test
	void Redis_snapshot_연결_예외는_불일치로_바꾸지_않고_그대로_전파한다() {
		when(redisTemplate.execute(
			org.mockito.ArgumentMatchers.same(readSnapshotScript),
			org.mockito.ArgumentMatchers.<String>anyList(),
			org.mockito.ArgumentMatchers.any(Object[].class)
		)).thenThrow(new RuntimeException("redis unavailable"));

		assertThatThrownBy(service::getPopularMenuRankings)
			.isInstanceOf(RuntimeException.class)
			.hasMessage("redis unavailable");
	}

	@Test
	void Redis가_DATA여도_새_PAID_주문이_Consumer에_반영되지_않으면_DB_snapshot으로_응답한다() {
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(valueOperations.setIfAbsent(any(), any(), any(Duration.class))).thenReturn(false);
		when(orderRepository.findDailyPaidMenuOrderCounts(any(), any())).thenReturn(List.of(
			new DailyMenuOrderCount(LocalDate.of(2026, 7, 15), 7L, 2L)
		));
		stubReadSnapshot("DATA|1|1|7=1", "|||", "|||", "|||", "|||", "|||", "|||");

		assertThat(service.getPopularMenuRankings())
			.containsExactly(new PopularMenuRanking(7L, 2L));
		verify(valueOperations).setIfAbsent(eq("coffee:ranking:rebuilding"), any(), any(Duration.class));
	}

	@Test
	void Redis가_EMPTY여도_새_PAID_주문이_Consumer에_반영되지_않으면_DB_snapshot으로_응답한다() {
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(valueOperations.setIfAbsent(any(), any(), any(Duration.class))).thenReturn(false);
		when(orderRepository.findDailyPaidMenuOrderCounts(any(), any())).thenReturn(List.of(
			new DailyMenuOrderCount(LocalDate.of(2026, 7, 15), 7L, 1L)
		));
		stubReadSnapshot("EMPTY|0|0|", "|||", "|||", "|||", "|||", "|||", "|||");

		assertThat(service.getPopularMenuRankings())
			.containsExactly(new PopularMenuRanking(7L, 1L));
	}

	@Test
	void Outbox_PENDING_PROCESSING_FAILED와_Kafka_DLT_지연은_PAID_원장_응답을_누락시키지_않는다() {
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(valueOperations.setIfAbsent(any(), any(), any(Duration.class))).thenReturn(false);
		when(orderRepository.findDailyPaidMenuOrderCounts(any(), any())).thenReturn(List.of(
			new DailyMenuOrderCount(LocalDate.of(2026, 7, 15), 7L, 4L)
		));
		stubReadSnapshot("DATA|1|1|7=1", "|||", "|||", "|||", "|||", "|||", "|||");

		assertThat(service.getPopularMenuRankings())
			.containsExactly(new PopularMenuRanking(7L, 4L));
	}

	@Test
	void 여러_번_재구성을_시도해도_다른_서버가_잠금을_보유하면_오래된_Redis를_반환하지_않는다() {
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(valueOperations.setIfAbsent(any(), any(), any(Duration.class))).thenReturn(false);
		when(orderRepository.findDailyPaidMenuOrderCounts(any(), any())).thenReturn(List.of(
			new DailyMenuOrderCount(LocalDate.of(2026, 7, 15), 7L, 3L)
		));
		stubReadSnapshot("DATA|1|1|7=1", "|||", "|||", "|||", "|||", "|||", "|||");

		assertThat(service.getPopularMenuRankings())
			.containsExactly(new PopularMenuRanking(7L, 3L));
		assertThat(service.getPopularMenuRankings())
			.containsExactly(new PopularMenuRanking(7L, 3L));
	}

	@Test
	void Redis가_비어있고_PAID_주문이_있으면_일자별_ZSET을_복구한다() {
		// given
		when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(valueOperations.setIfAbsent(
			org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), any(Duration.class)
		)).thenReturn(true);
		when(orderEventRepository.findPaidEventsForRankingRebuild(any(), any())).thenReturn(List.of());
		when(zSetOperations.rangeWithScores(any(), eq(0L), eq(-1L))).thenReturn(Set.of());
		when(orderRepository.findDailyPaidMenuOrderCounts(any(), any())).thenReturn(List.of(
			new DailyMenuOrderCount(LocalDate.of(2026, 7, 14), 7L, 2L),
			new DailyMenuOrderCount(LocalDate.of(2026, 7, 15), 3L, 4L)
		));

		// when
		List<PopularMenuRanking> rankings = service.getPopularMenuRankings();

		// then
		assertThat(rankings).containsExactly(
			new PopularMenuRanking(3L, 4L),
			new PopularMenuRanking(7L, 2L)
		);
	}

	@Test
	void DATA_상태의_일자별_ZSET이_하나라도_없으면_DB로_전체_기간을_복구한다() {
		// given
		when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(valueOperations.get(org.mockito.ArgumentMatchers.anyString())).thenReturn("DATA");
		when(redisTemplate.hasKey(org.mockito.ArgumentMatchers.anyString())).thenAnswer(invocation ->
			!invocation.getArgument(0, String.class).endsWith("2026-07-09")
		);
		when(valueOperations.setIfAbsent(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), any(Duration.class)))
			.thenReturn(true);
		when(orderEventRepository.findPaidEventsForRankingRebuild(any(), any())).thenReturn(List.of());
		when(orderRepository.findDailyPaidMenuOrderCounts(any(), any())).thenReturn(List.of(
			new DailyMenuOrderCount(LocalDate.of(2026, 7, 15), 3L, 4L)
		));
		when(zSetOperations.rangeWithScores(any(), eq(0L), eq(-1L))).thenReturn(Set.of());

		// when
		List<PopularMenuRanking> rankings = service.getPopularMenuRankings();

		// then
		assertThat(rankings).containsExactly(new PopularMenuRanking(3L, 4L));
	}

	@Test
	void 다른_요청이_재구성_잠금을_보유하면_DB_집계값만_반환하고_Redis를_수정하지_않는다() {
		// given
		when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(valueOperations.setIfAbsent(org.mockito.ArgumentMatchers.anyString(), eq("1"), any(Duration.class))).thenReturn(false);
		when(zSetOperations.rangeWithScores(any(), eq(0L), eq(-1L))).thenReturn(Set.of());
		when(orderRepository.findDailyPaidMenuOrderCounts(any(), any())).thenReturn(List.of(
			new DailyMenuOrderCount(LocalDate.of(2026, 7, 15), 3L, 4L)
		));

		// when
		List<PopularMenuRanking> rankings = service.getPopularMenuRankings();

		// then
		assertThat(rankings).containsExactly(new PopularMenuRanking(3L, 4L));
		verify(zSetOperations, org.mockito.Mockito.never()).add(any(), any(), any(Double.class));
	}

	@Test
	void lease_연장에_실패하면_재구성을_중단하고_이후_Redis_점수표를_수정하지_않는다() {
		// given
		ScheduledExecutorService scheduler = org.mockito.Mockito.mock(ScheduledExecutorService.class);
		@SuppressWarnings("unchecked")
		ScheduledFuture<?> future = org.mockito.Mockito.mock(ScheduledFuture.class);
		ArgumentCaptor<Runnable> renewalCaptor = ArgumentCaptor.forClass(Runnable.class);
		org.mockito.Mockito.doReturn(future).when(scheduler)
			.scheduleAtFixedRate(renewalCaptor.capture(), eq(20L), eq(20L), eq(TimeUnit.SECONDS));
		when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(valueOperations.setIfAbsent(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), any(Duration.class)))
			.thenReturn(true);
		when(zSetOperations.rangeWithScores(any(), eq(0L), eq(-1L))).thenReturn(Set.of());
		when(redisTemplate.execute(
			org.mockito.ArgumentMatchers.<org.springframework.data.redis.core.script.RedisScript<Long>>any(),
			org.mockito.ArgumentMatchers.<String>anyList(),
			org.mockito.ArgumentMatchers.any(Object[].class)
		)).thenReturn(0L);
		when(redisTemplate.execute(
			org.mockito.ArgumentMatchers.same(readSnapshotScript),
			org.mockito.ArgumentMatchers.<String>anyList(),
			org.mockito.ArgumentMatchers.any(Object[].class)
		)).thenReturn(emptyRedisSnapshot()).thenAnswer(invocation -> {
			renewalCaptor.getValue().run();
			return emptyRedisSnapshot();
		});
		when(orderRepository.findDailyPaidMenuOrderCounts(any(), any())).thenReturn(List.of(
			new DailyMenuOrderCount(LocalDate.of(2026, 7, 15), 3L, 4L)
		));
		PopularMenuRankingService leaseService = new PopularMenuRankingService(
			redisTemplate, orderRepository, orderEventRepository, Duration.ofDays(8), Duration.ofMinutes(1), Duration.ofSeconds(20),
			new org.springframework.data.redis.core.script.DefaultRedisScript<>("return 1", Long.class),
			new org.springframework.data.redis.core.script.DefaultRedisScript<>("return 1", Long.class),
			new org.springframework.data.redis.core.script.DefaultRedisScript<>("return 1", Long.class),
			new org.springframework.data.redis.core.script.DefaultRedisScript<>("return 1", Long.class),
			new org.springframework.data.redis.core.script.DefaultRedisScript<>("return 1", Long.class), readSnapshotScript, scheduler, clock
		);

		// when
		org.assertj.core.api.ThrowableAssert.ThrowingCallable recover = leaseService::getPopularMenuRankings;

		// then
		assertThat(leaseService.getPopularMenuRankings()).containsExactly(new PopularMenuRanking(3L, 4L));
		verify(zSetOperations, org.mockito.Mockito.never()).add(any(), any(), any(Double.class));
		verify(future).cancel(false);
	}

	@Test
	void 소유권을_잃으면_이번_재구성이_만든_이벤트_마커만_조건부로_정리한다() {
		// given
		ScheduledExecutorService scheduler = org.mockito.Mockito.mock(ScheduledExecutorService.class);
		@SuppressWarnings("unchecked")
		ScheduledFuture<?> future = org.mockito.Mockito.mock(ScheduledFuture.class);
		ArgumentCaptor<Runnable> renewalCaptor = ArgumentCaptor.forClass(Runnable.class);
		org.mockito.Mockito.doReturn(future).when(scheduler)
			.scheduleAtFixedRate(renewalCaptor.capture(), eq(20L), eq(20L), eq(TimeUnit.SECONDS));
		org.springframework.data.redis.core.script.DefaultRedisScript<Long> releaseScript =
			new org.springframework.data.redis.core.script.DefaultRedisScript<>("return 1", Long.class);
		org.springframework.data.redis.core.script.DefaultRedisScript<Long> renewScript =
			new org.springframework.data.redis.core.script.DefaultRedisScript<>("return 1", Long.class);
		org.springframework.data.redis.core.script.DefaultRedisScript<Long> cleanupMarkerScript =
			new org.springframework.data.redis.core.script.DefaultRedisScript<>("return 1", Long.class);
		org.springframework.data.redis.core.script.DefaultRedisScript<Long> cleanupRebuildScript =
			new org.springframework.data.redis.core.script.DefaultRedisScript<>("return 1", Long.class);
		org.springframework.data.redis.core.script.DefaultRedisScript<Long> rebuildWriteScript =
			new org.springframework.data.redis.core.script.DefaultRedisScript<>("return 1", Long.class);
		when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(valueOperations.setIfAbsent(eq("coffee:ranking:rebuilding"), org.mockito.ArgumentMatchers.anyString(), any(Duration.class)))
			.thenReturn(true);
		when(valueOperations.setIfAbsent(eq("coffee:ranking:processed:42"), org.mockito.ArgumentMatchers.anyString(), any(Duration.class)))
			.thenAnswer(invocation -> {
				renewalCaptor.getValue().run();
				return true;
			});
		when(zSetOperations.rangeWithScores(any(), eq(0L), eq(-1L))).thenReturn(Set.of());
		when(orderRepository.findDailyPaidMenuOrderCounts(any(), any())).thenReturn(List.of());
		when(orderEventRepository.findPaidEventsForRankingRebuild(any(), any()))
			.thenReturn(List.of(new com.example.coffeeordersystem.ranking.dto.RebuildOrderEvent(42L), new com.example.coffeeordersystem.ranking.dto.RebuildOrderEvent(43L)));
		when(redisTemplate.execute(org.mockito.ArgumentMatchers.<org.springframework.data.redis.core.script.RedisScript<Long>>any(),
			org.mockito.ArgumentMatchers.<String>anyList(), org.mockito.ArgumentMatchers.any(Object[].class))).thenReturn(-1L);
		when(redisTemplate.execute(
			org.mockito.ArgumentMatchers.same(readSnapshotScript),
			org.mockito.ArgumentMatchers.<String>anyList(),
			org.mockito.ArgumentMatchers.any(Object[].class)
		)).thenReturn(emptyRedisSnapshot());
		PopularMenuRankingService leaseService = new PopularMenuRankingService(
			redisTemplate, orderRepository, orderEventRepository, Duration.ofDays(8), Duration.ofMinutes(1), Duration.ofSeconds(20),
			releaseScript, renewScript, cleanupMarkerScript, cleanupRebuildScript, rebuildWriteScript, readSnapshotScript, scheduler, clock
		);

		// when
		org.assertj.core.api.ThrowableAssert.ThrowingCallable recover = leaseService::getPopularMenuRankings;

		// then
		assertThat(leaseService.getPopularMenuRankings()).isEmpty();
		verify(redisTemplate).execute(org.mockito.ArgumentMatchers.same(cleanupRebuildScript),
			org.mockito.ArgumentMatchers.<String>anyList(), org.mockito.ArgumentMatchers.any(Object[].class));
	}

	private Set<ZSetOperations.TypedTuple<String>> tuples(Object... membersAndScores) {
		java.util.LinkedHashSet<ZSetOperations.TypedTuple<String>> tuples = new java.util.LinkedHashSet<>();
		for (int index = 0; index < membersAndScores.length; index += 2) {
			tuples.add(new DefaultTypedTuple((String) membersAndScores[index], ((Number) membersAndScores[index + 1]).doubleValue()));
		}
		return tuples;
	}

	private String emptyRedisSnapshot() {
		return redisSnapshot("|||", "|||", "|||", "|||", "|||", "|||", "|||");
	}

	private String redisSnapshot(String... dailySnapshots) {
		return String.join(";", dailySnapshots);
	}

	private void stubReadSnapshot(String... dailySnapshots) {
		when(redisTemplate.execute(
			org.mockito.ArgumentMatchers.same(readSnapshotScript),
			org.mockito.ArgumentMatchers.<String>anyList(),
			org.mockito.ArgumentMatchers.any(Object[].class)
		)).thenReturn(redisSnapshot(dailySnapshots));
	}

	@SuppressWarnings("unchecked")
	private ArgumentCaptor<List<String>> listCaptor() {
		return (ArgumentCaptor<List<String>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(List.class);
	}

	private record DefaultTypedTuple(String value, Double score) implements ZSetOperations.TypedTuple<String> {
		@Override
		public String getValue() {
			return value;
		}

		@Override
		public Double getScore() {
			return score;
		}

		@Override
		public int compareTo(ZSetOperations.TypedTuple<String> other) {
			return 0;
		}
	}
}
