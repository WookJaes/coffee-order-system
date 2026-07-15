package com.example.coffeeordersystem.menu.dto;

import com.example.coffeeordersystem.menu.entity.Menu;
import com.example.coffeeordersystem.ranking.dto.PopularMenuRanking;

public record PopularMenuResponse(Integer rank, Long menuId, String menuName, Long orderCount) {

	public static PopularMenuResponse from(Integer rank, Menu menu, PopularMenuRanking ranking) {
		return new PopularMenuResponse(rank, menu.getId(), menu.getName(), ranking.orderCount());
	}
}
