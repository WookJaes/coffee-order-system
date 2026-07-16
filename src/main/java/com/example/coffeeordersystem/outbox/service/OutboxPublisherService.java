package com.example.coffeeordersystem.outbox.service;

import com.example.coffeeordersystem.outbox.config.OutboxPublisherProperties;
import com.example.coffeeordersystem.outbox.dto.ClaimedOrderEvent;
import com.example.coffeeordersystem.outbox.dto.OrderPaidEvent;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
			CompletableFuture<?> sendResult = CompletableFuture.supplyAsync(
				() -> kafkaTemplate.send(properties.topic(), event.orderId().toString(), event)
			).thenCompose(result -> result);
			if (awaitKafkaPublishWhileRenewingLease(sendResult, event.eventId(), claimedEvent.token())) {
				if (!outboxEventCompletionService.markSent(event.eventId(), claimedEvent.token())) {
					log.warn("Outbox event processing lease lost before completion. eventId={}", event.eventId());
				}
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			markFailedIfClaimed(event.eventId(), claimedEvent.token(), exception);
		} catch (ExecutionException | RuntimeException exception) {
			markFailedIfClaimed(event.eventId(), claimedEvent.token(), exception);
			log.warn("Outbox event publish failed. eventId={}", event.eventId(), exception);
		}
	}

	private void markFailedIfClaimed(Long eventId, String token, Throwable throwable) {
		if (!outboxEventCompletionService.markFailed(eventId, token, throwable)) {
			log.warn("Outbox event processing lease lost before failure completion. eventId={}", eventId);
		}
	}

	private boolean awaitKafkaPublishWhileRenewingLease(
		CompletableFuture<?> sendResult,
		Long eventId,
		String token
	) throws InterruptedException, ExecutionException {
		long renewalIntervalMillis = Math.max(1, properties.processingTimeout().toMillis() / 3);

		while (true) {
			try {
				sendResult.get(renewalIntervalMillis, TimeUnit.MILLISECONDS);
				return true;
			} catch (TimeoutException exception) {
				if (!outboxEventClaimService.renewProcessingLease(eventId, token)) {
					log.warn("Outbox event processing lease lost. eventId={}", eventId);
					return false;
				}
			}
		}
	}
}
