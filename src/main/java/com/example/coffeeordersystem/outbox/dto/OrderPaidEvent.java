package com.example.coffeeordersystem.outbox.dto;

public record OrderPaidEvent(
	Long eventId,
	Long orderId,
	Long userId,
	Long menuId,
	Integer paymentAmount
) {
}
