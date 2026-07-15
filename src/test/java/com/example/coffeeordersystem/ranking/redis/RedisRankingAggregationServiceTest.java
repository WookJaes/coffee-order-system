package com.example.coffeeordersystem.ranking.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.coffeeordersystem.outbox.dto.OrderPaidEvent;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

class RedisRankingAggregationServiceTest {

	private final StringRedisTemplate redisTemplate = org.mockito.Mockito.mock(StringRedisTemplate.class);
	private final DefaultRedisScript<Long> processOnceScript = new DefaultRedisScript<>("return 1", Long.class);
	private final RedisRankingAggregationService service = new RedisRankingAggregationService(
		redisTemplate,
		Duration.ofDays(8),
		Clock.fixed(Instant.parse("2026-07-15T01:00:00Z"), ZoneId.of("Asia/Seoul")),
		processOnceScript
	);

	@Test
	void 새_이벤트는_해당_날짜_ZSET의_메뉴_주문수를_한번_증가시킨다() {
		// given
		when(redisTemplate.execute(
			org.mockito.ArgumentMatchers.<RedisScript<Long>>any(),
			org.mockito.ArgumentMatchers.<String>anyList(),
			org.mockito.ArgumentMatchers.any(Object[].class)
		)).thenReturn(1L);


		// when
		boolean aggregated = service.aggregate(new OrderPaidEvent(42L, 10L, 3L, 7L, 4_500));

		// then
		assertThat(aggregated).isTrue();
		ArgumentCaptor<List<String>> keyCaptor = listCaptor();
		ArgumentCaptor<Object[]> argumentCaptor = ArgumentCaptor.forClass(Object[].class);
		verify(redisTemplate).execute(org.mockito.ArgumentMatchers.same(processOnceScript), keyCaptor.capture(), argumentCaptor.capture());
		assertThat(keyCaptor.getValue()).containsExactly(
			"coffee:ranking:processed:42",
			"coffee:ranking:2026-07-15"
		);
		assertThat(argumentCaptor.getValue()).containsExactly("691200", "1", "7");
	}

	@Test
	void 이미_처리한_이벤트는_ZSET_점수를_다시_증가시키지_않는다() {
		// given
		when(redisTemplate.execute(
			org.mockito.ArgumentMatchers.<RedisScript<Long>>any(),
			org.mockito.ArgumentMatchers.<String>anyList(),
			org.mockito.ArgumentMatchers.any(Object[].class)
		)).thenReturn(0L);


		// when
		boolean aggregated = service.aggregate(new OrderPaidEvent(42L, 10L, 3L, 7L, 4_500));

		// then
		assertThat(aggregated).isFalse();
	}

	@SuppressWarnings("unchecked")
	private ArgumentCaptor<List<String>> listCaptor() {
		return (ArgumentCaptor<List<String>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(List.class);
	}
}
