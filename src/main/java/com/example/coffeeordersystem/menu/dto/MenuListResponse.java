package com.example.coffeeordersystem.menu.dto;

import com.example.coffeeordersystem.menu.entity.Menu;

public record MenuListResponse(Long menuId, String name, Integer price) {

	public static MenuListResponse from(Menu menu) {
		return new MenuListResponse(menu.getId(), menu.getName(), menu.getPrice());
	}
}
