package com.example.coffeeordersystem.outbox.dto;

public record ClaimedOrderEvent(
	String token,
	OrderPaidEvent message
) {
}
