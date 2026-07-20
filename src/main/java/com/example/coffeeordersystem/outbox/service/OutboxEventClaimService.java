package com.example.coffeeordersystem.outbox.service;

import com.example.coffeeordersystem.order.entity.OrderEventStatus;
import com.example.coffeeordersystem.order.repository.OrderEventRepository;
import com.example.coffeeordersystem.outbox.config.OutboxPublisherProperties;
import com.example.coffeeordersystem.outbox.dto.ClaimedOrderEvent;
import com.example.coffeeordersystem.outbox.dto.ClaimedOrderEventMessage;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OutboxEventClaimService {

	private final OrderEventRepository orderEventRepository;
	private final OutboxPublisherProperties properties;

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public List<ClaimedOrderEvent> claimPendingEvents() {
		LocalDateTime now = LocalDateTime.now();

		orderEventRepository.recoverStaleProcessing(now, now.minus(properties.processingTimeout()));

		String token = UUID.randomUUID().toString();
		List<Long> candidateIds = orderEventRepository.findCandidateIds(
			OrderEventStatus.PENDING,
			now,
			PageRequest.of(0, properties.batchSize())
		);

		if (candidateIds.isEmpty()) {
			return List.of();
		}

		orderEventRepository.claimPendingBatch(candidateIds, token, now);

		return orderEventRepository.findClaimedMessagesByProcessingToken(token).stream()
			.map(ClaimedOrderEventMessage::toOrderPaidEvent)
			.map(message -> new ClaimedOrderEvent(token, message))
			.toList();
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public boolean renewProcessingLeases(String token) {
		return orderEventRepository.renewProcessingLeases(token, LocalDateTime.now()) > 0;
	}

}
