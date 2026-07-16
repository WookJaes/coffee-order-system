package com.example.coffeeordersystem.outbox.service;

import com.example.coffeeordersystem.outbox.config.OutboxPublisherProperties;
import com.example.coffeeordersystem.outbox.dto.ClaimedOrderEvent;
import com.example.coffeeordersystem.outbox.dto.OrderPaidEvent;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class OutboxPublisherService {

	private final OutboxEventClaimService outboxEventClaimService;
	private final OutboxEventCompletionService outboxEventCompletionService;
	private final KafkaTemplate<String, OrderPaidEvent> kafkaTemplate;
	@Qualifier("outboxKafkaSendExecutor")
	private final AsyncTaskExecutor outboxKafkaSendExecutor;
	private final OutboxPublisherProperties properties;

	public void publishPendingEvents() {
		outboxEventClaimService.claimPendingEvents().forEach(this::publish);
	}

	private void publish(ClaimedOrderEvent claimedEvent) {
		OrderPaidEvent event = claimedEvent.message();
		boolean kafkaPublished;

		try {
			kafkaPublished = publishKafkaWhileRenewingLease(event, claimedEvent.token());
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			markFailedIfClaimed(event.eventId(), claimedEvent.token(), exception);
			return;
		} catch (ExecutionException | RuntimeException exception) {
			markFailedIfClaimed(event.eventId(), claimedEvent.token(), exception);
			log.warn("Outbox event publish failed. eventId={}", event.eventId(), exception);
			return;
		}

		if (!kafkaPublished) {
			return;
		}

		try {
			if (!outboxEventCompletionService.markSent(event.eventId(), claimedEvent.token())) {
				log.warn("Outbox event processing lease lost before completion. eventId={}", event.eventId());
			}
		} catch (RuntimeException exception) {
			log.warn("Outbox event Kafka publish succeeded but DB completion failed. eventId={}", event.eventId(), exception);
		}
	}

	private boolean publishKafkaWhileRenewingLease(OrderPaidEvent event, String token)
		throws InterruptedException, ExecutionException {
		CompletableFuture<CompletableFuture<?>> sendInvocation = new CompletableFuture<>();
		AtomicBoolean sendStarted = new AtomicBoolean(false);
		Future<?> sendTask = outboxKafkaSendExecutor.submit(() -> {
			sendStarted.set(true);
			try {
				sendInvocation.complete(kafkaTemplate.send(properties.topic(), event.orderId().toString(), event));
			} catch (RuntimeException exception) {
				sendInvocation.completeExceptionally(exception);
			}
		});

		CompletableFuture<?> sendResult = awaitSendInvocationWhileRenewingLease(
			sendInvocation,
			sendTask,
			sendStarted,
			event.eventId(),
			token
		);
		return sendResult != null && awaitKafkaPublishWhileRenewingLease(sendResult, event.eventId(), token);
	}

	private CompletableFuture<?> awaitSendInvocationWhileRenewingLease(
		CompletableFuture<CompletableFuture<?>> sendInvocation,
		Future<?> sendTask,
		AtomicBoolean sendStarted,
		Long eventId,
		String token
	) throws InterruptedException, ExecutionException {
		long renewalIntervalMillis = renewalIntervalMillis();

		while (true) {
			try {
				return sendInvocation.get(renewalIntervalMillis, TimeUnit.MILLISECONDS);
			} catch (TimeoutException exception) {
				if (!sendStarted.get()) {
					sendTask.cancel(false);
					throw new IllegalStateException("Outbox Kafka send 작업이 시작되지 않았습니다.");
				}
				if (!outboxEventClaimService.renewProcessingLease(eventId, token)) {
					log.warn("Outbox event processing lease lost. eventId={}", eventId);
					return null;
				}
			}
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
		long renewalIntervalMillis = renewalIntervalMillis();

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

	private long renewalIntervalMillis() {
		return properties.processingTimeout().toMillis() / 3;
	}
}
