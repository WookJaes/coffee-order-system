package com.example.coffeeordersystem.ranking.dto;

public record DailyMenuOrderCount(
	Object orderedDate,
	Long menuId,
	Long orderCount
) {
}
