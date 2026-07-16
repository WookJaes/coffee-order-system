package com.example.coffeeordersystem.outbox.dto;

import java.time.LocalDateTime;

public record OrderPaidEvent(
	Long eventId,
	Long orderId,
	Long userId,
	Long menuId,
	Integer paymentAmount,
	LocalDateTime orderedAt
) {
}
