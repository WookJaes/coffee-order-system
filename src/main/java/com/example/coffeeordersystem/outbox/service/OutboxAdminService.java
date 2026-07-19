package com.example.coffeeordersystem.outbox.service;

import com.example.coffeeordersystem.global.exception.BusinessException;
import com.example.coffeeordersystem.global.exception.ErrorCode;
import com.example.coffeeordersystem.order.entity.OrderEvent;
import com.example.coffeeordersystem.order.entity.OrderEventStatus;
import com.example.coffeeordersystem.order.repository.OrderEventRepository;
import com.example.coffeeordersystem.outbox.dto.OutboxReprocessResponse;
import com.example.coffeeordersystem.outbox.dto.OutboxStatusCountsResponse;

import java.time.LocalDateTime;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OutboxAdminService {

	private final OrderEventRepository orderEventRepository;

	@Transactional
	public OutboxReprocessResponse reprocessFailedEvent(Long eventId) {
		OrderEvent event = orderEventRepository.findByIdForUpdate(eventId)
			.orElseThrow(() -> new BusinessException(ErrorCode.OUTBOX_EVENT_NOT_FOUND));
		if (event.getStatus() != OrderEventStatus.FAILED) {
			throw new BusinessException(ErrorCode.OUTBOX_EVENT_NOT_REPROCESSABLE);
		}

		event.reprocess(LocalDateTime.now());
		return new OutboxReprocessResponse(event.getId(), event.getStatus().name(), event.getNextAttemptAt());
	}

	@Transactional(readOnly = true)
	public OutboxStatusCountsResponse getStatusCounts() {
		return new OutboxStatusCountsResponse(
			orderEventRepository.countByStatus(OrderEventStatus.PENDING),
			orderEventRepository.countByStatus(OrderEventStatus.PROCESSING),
			orderEventRepository.countByStatus(OrderEventStatus.FAILED)
		);
	}
}
