package com.example.coffeeordersystem.ranking.consumer;

import com.example.coffeeordersystem.order.repository.OrderRepository;
import com.example.coffeeordersystem.outbox.dto.OrderPaidEvent;
import com.example.coffeeordersystem.ranking.redis.RedisRankingAggregationService;

import lombok.RequiredArgsConstructor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "ranking.consumer", name = "enabled", havingValue = "true")
public class OrderPaidRankingConsumer {

	private final RedisRankingAggregationService aggregationService;
	private final OrderRepository orderRepository;

	@KafkaListener(
		topics = "${ranking.consumer.topic}",
		groupId = "${ranking.consumer.group-id}",
		containerFactory = "orderPaidRankingKafkaListenerContainerFactory"
	)
	public void consume(OrderPaidEvent event) {
		aggregationService.aggregate(withOrderedAt(event));
	}

	private OrderPaidEvent withOrderedAt(OrderPaidEvent event) {
		if (event.orderedAt() != null) {
			return event;
		}

		return orderRepository.findById(event.orderId())
			.map(order -> new OrderPaidEvent(
				event.eventId(),
				event.orderId(),
				event.userId(),
				event.menuId(),
				event.paymentAmount(),
				order.getOrderedAt()
			))
			.orElseThrow(() -> new IllegalArgumentException("기존 주문 완료 이벤트의 주문을 찾을 수 없습니다."));
	}
}
