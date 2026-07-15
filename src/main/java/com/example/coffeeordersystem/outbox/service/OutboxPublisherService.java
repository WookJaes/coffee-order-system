package com.example.coffeeordersystem.outbox.service;

import com.example.coffeeordersystem.outbox.config.OutboxPublisherProperties;
import com.example.coffeeordersystem.outbox.dto.ClaimedOrderEvent;
import com.example.coffeeordersystem.outbox.dto.OrderPaidEvent;

import java.util.concurrent.ExecutionException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class OutboxPublisherService {

	private final OutboxEventClaimService outboxEventClaimService;
	private final OutboxEventCompletionService outboxEventCompletionService;
	private final KafkaTemplate<String, OrderPaidEvent> kafkaTemplate;
	private final OutboxPublisherProperties properties;

	public void publishPendingEvents() {
		outboxEventClaimService.claimPendingEvents().forEach(this::publish);
	}

	private void publish(ClaimedOrderEvent claimedEvent) {
		OrderPaidEvent event = claimedEvent.message();

		try {
			kafkaTemplate.send(properties.topic(), event.orderId().toString(), event).get();
			outboxEventCompletionService.markSent(event.eventId(), claimedEvent.token());
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			outboxEventCompletionService.markFailed(event.eventId(), claimedEvent.token(), exception);
		} catch (ExecutionException | RuntimeException exception) {
			outboxEventCompletionService.markFailed(event.eventId(), claimedEvent.token(), exception);
			log.warn("Outbox event publish failed. eventId={}", event.eventId(), exception);
		}
	}
}
