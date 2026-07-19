package com.example.coffeeordersystem.dataplatform.client;

public record DataPlatformOrderPaidRequest(
	Long eventId,
	Long userId,
	Long menuId,
	Integer paymentAmount
) {

	public String idempotencyKey() {
		return "order-paid:" + eventId;
	}
}
