package com.example.coffeeordersystem.ranking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.coffeeordersystem.order.repository.OrderRepository;
import com.example.coffeeordersystem.ranking.dto.DailyMenuOrderCount;
import com.example.coffeeordersystem.ranking.dto.PopularMenuRanking;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

class PopularMenuRankingServiceTest {

	private final StringRedisTemplate redisTemplate = org.mockito.Mockito.mock(StringRedisTemplate.class);
	private final ZSetOperations<String, String> zSetOperations = org.mockito.Mockito.mock(ZSetOperations.class);
	private final OrderRepository orderRepository = org.mockito.Mockito.mock(OrderRepository.class);
	private final Clock clock = Clock.fixed(Instant.parse("2026-07-15T01:00:00Z"), ZoneId.of("Asia/Seoul"));
	private final PopularMenuRankingService service = new PopularMenuRankingService(
		redisTemplate,
		orderRepository,
		Duration.ofDays(8),
		clock
	);

	@Test
	void 요청일을_포함한_7일_ZSET_점수를_합산해_Top3를_반환한다() {
		// given
		when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
		when(zSetOperations.rangeWithScores(any(), eq(0L), eq(-1L)))
			.thenReturn(tuples("1", 2, "2", 1))
			.thenReturn(tuples("1", 3, "3", 5))
			.thenReturn(Set.of())
			.thenReturn(Set.of())
			.thenReturn(Set.of())
			.thenReturn(Set.of())
			.thenReturn(Set.of());

		// when
		List<PopularMenuRanking> rankings = service.getPopularMenuRankings();

		// then
		assertThat(rankings).containsExactly(
			new PopularMenuRanking(1L, 5L),
			new PopularMenuRanking(3L, 5L),
			new PopularMenuRanking(2L, 1L)
		);
		ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
		verify(zSetOperations, org.mockito.Mockito.times(7)).rangeWithScores(keyCaptor.capture(), eq(0L), eq(-1L));
		assertThat(keyCaptor.getAllValues()).containsExactly(
			"coffee:ranking:2026-07-15", "coffee:ranking:2026-07-14", "coffee:ranking:2026-07-13",
			"coffee:ranking:2026-07-12", "coffee:ranking:2026-07-11", "coffee:ranking:2026-07-10",
			"coffee:ranking:2026-07-09"
		);
	}

	@Test
	void Redis가_비어있고_PAID_주문이_있으면_일자별_ZSET을_복구한다() {
		// given
		when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
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
		verify(zSetOperations).incrementScore("coffee:ranking:2026-07-14", "7", 2D);
		verify(zSetOperations).incrementScore("coffee:ranking:2026-07-15", "3", 4D);
		verify(redisTemplate).expire("coffee:ranking:2026-07-14", Duration.ofDays(8));
		verify(redisTemplate).expire("coffee:ranking:2026-07-15", Duration.ofDays(8));
	}

	private Set<ZSetOperations.TypedTuple<String>> tuples(Object... membersAndScores) {
		java.util.LinkedHashSet<ZSetOperations.TypedTuple<String>> tuples = new java.util.LinkedHashSet<>();
		for (int index = 0; index < membersAndScores.length; index += 2) {
			tuples.add(new DefaultTypedTuple((String) membersAndScores[index], ((Number) membersAndScores[index + 1]).doubleValue()));
		}
		return tuples;
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
