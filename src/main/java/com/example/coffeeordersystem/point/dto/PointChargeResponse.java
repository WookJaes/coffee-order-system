package com.example.coffeeordersystem.point.dto;

import com.example.coffeeordersystem.point.entity.Point;

public record PointChargeResponse(
	Long userId,
	Integer chargedAmount,
	Integer balance
) {
	public static PointChargeResponse of(Point point, Integer chargedAmount) {
		return new PointChargeResponse(
			point.getUser().getId(),
			chargedAmount,
			point.getBalance()
		);
	}
}
