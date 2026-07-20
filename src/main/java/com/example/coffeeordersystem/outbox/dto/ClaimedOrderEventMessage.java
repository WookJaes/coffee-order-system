package com.example.coffeeordersystem.outbox.dto;

import java.time.LocalDateTime;

public record ClaimedOrderEventMessage(
	Long eventId,
	Long orderId,
	Long userId,
	Long menuId,
	Integer paymentAmount,
	LocalDateTime orderedAt
) {
	public OrderPaidEvent toOrderPaidEvent() {
		return new OrderPaidEvent(eventId, orderId, userId, menuId, paymentAmount, orderedAt);
	}
}
