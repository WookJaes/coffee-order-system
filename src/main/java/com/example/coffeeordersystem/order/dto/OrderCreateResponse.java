package com.example.coffeeordersystem.order.dto;

import com.example.coffeeordersystem.order.entity.Order;
import com.example.coffeeordersystem.point.entity.Point;

public record OrderCreateResponse(
	Long orderId,
	Long userId,
	Long menuId,
	Integer quantity,
	Integer paymentAmount,
	Integer remainingPoint,
	String status
) {
	public static OrderCreateResponse from(Order order, Point point) {
		return new OrderCreateResponse(
			order.getId(),
			order.getUser().getId(),
			order.getMenu().getId(),
			order.getQuantity(),
			order.getOrderPrice(),
			point.getBalance(),
			order.getStatus().name()
		);
	}
}
