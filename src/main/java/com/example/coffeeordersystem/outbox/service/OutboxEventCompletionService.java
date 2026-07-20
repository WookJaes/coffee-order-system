package com.example.coffeeordersystem.outbox.service;

import com.example.coffeeordersystem.order.entity.OrderEvent;
import com.example.coffeeordersystem.order.entity.OrderEventStatus;
import com.example.coffeeordersystem.order.repository.OrderEventRepository;
import com.example.coffeeordersystem.outbox.config.OutboxPublisherProperties;

import java.time.LocalDateTime;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OutboxEventCompletionService {

	private final OrderEventRepository orderEventRepository;
	private final OutboxPublisherProperties properties;

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public boolean markSent(Long eventId, String token) {
		OrderEvent event = findClaimedEvent(eventId, token);
		if (event == null) {
			return false;
		}
		event.markSent();
		return true;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public boolean markFailed(Long eventId, String token, Throwable throwable) {
		OrderEvent event = findClaimedEvent(eventId, token);
		if (event == null) {
			return false;
		}
		event.markPublishFailed(
			properties.maxRetryCount(),
			properties.retryBackoff(),
			LocalDateTime.now(),
			throwable.getMessage() == null ? throwable.getClass().getSimpleName() : throwable.getMessage()
		);
		return true;
	}

	private OrderEvent findClaimedEvent(Long eventId, String token) {
		OrderEvent event = orderEventRepository.findByIdForUpdate(eventId).orElseThrow();

		if (event.getStatus() != OrderEventStatus.PROCESSING || !token.equals(event.getProcessingToken())) {
			return null;
		}

		return event;
	}
}
