package com.example.coffeeordersystem.ranking.consumer;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.coffeeordersystem.order.entity.Order;
import com.example.coffeeordersystem.order.repository.OrderRepository;
import com.example.coffeeordersystem.outbox.dto.OrderPaidEvent;
import com.example.coffeeordersystem.ranking.redis.RedisRankingAggregationService;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class OrderPaidRankingConsumerTest {

	private final RedisRankingAggregationService aggregationService = org.mockito.Mockito.mock(RedisRankingAggregationService.class);
	private final OrderRepository orderRepository = org.mockito.Mockito.mock(OrderRepository.class);
	private final OrderPaidRankingConsumer consumer = new OrderPaidRankingConsumer(aggregationService, orderRepository);

	@Test
	void orderedAt이_없는_기존_메시지는_주문_원장의_주문_시각으로_집계한다() {
		// given
		OrderPaidEvent legacyEvent = new OrderPaidEvent(42L, 10L, 3L, 7L, 4_500, null);
		Order order = org.mockito.Mockito.mock(Order.class);
		LocalDateTime orderedAt = LocalDateTime.of(2026, 7, 14, 23, 59, 59);
		when(order.getOrderedAt()).thenReturn(orderedAt);
		when(orderRepository.findById(10L)).thenReturn(Optional.of(order));

		// when
		consumer.consume(legacyEvent);

		// then
		verify(aggregationService).aggregate(new OrderPaidEvent(42L, 10L, 3L, 7L, 4_500, orderedAt));
	}
}
