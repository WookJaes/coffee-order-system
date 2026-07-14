package com.example.coffeeordersystem.point.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;

public record PointChargeRequest(
	@NotNull(message = "사용자 ID는 필수입니다.")
	Long userId,

	@NotNull(message = "충전 금액은 필수입니다.")
	@Positive(message = "충전 금액은 1 이상이어야 합니다.")
	@Max(value = 100_000, message = "충전 금액은 100,000 이하여야 합니다.")
	Integer amount
) {
}
