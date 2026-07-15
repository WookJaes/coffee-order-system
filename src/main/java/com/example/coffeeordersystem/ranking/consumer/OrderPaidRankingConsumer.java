package com.example.coffeeordersystem.ranking.consumer;

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

	@KafkaListener(
		topics = "${ranking.consumer.topic}",
		groupId = "${ranking.consumer.group-id}",
		containerFactory = "orderPaidRankingKafkaListenerContainerFactory"
	)
	public void consume(OrderPaidEvent event) {
		aggregationService.aggregate(event);
	}
}
