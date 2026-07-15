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
	public void markSent(Long eventId, String token) {
		findClaimedEvent(eventId, token).markSent();
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void markFailed(Long eventId, String token, Throwable throwable) {
		findClaimedEvent(eventId, token).markPublishFailed(
			properties.maxRetryCount(),
			properties.retryBackoff(),
			LocalDateTime.now(),
			throwable.getMessage() == null ? throwable.getClass().getSimpleName() : throwable.getMessage()
		);
	}

	private OrderEvent findClaimedEvent(Long eventId, String token) {
		OrderEvent event = orderEventRepository.findByIdForUpdate(eventId).orElseThrow();

		if (event.getStatus() != OrderEventStatus.PROCESSING || !token.equals(event.getProcessingToken())) {
			throw new IllegalStateException("Outbox 이벤트 선점 정보가 유효하지 않습니다.");
		}

		return event;
	}
}
