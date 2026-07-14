package com.example.coffeeordersystem.menu.controller;

import com.example.coffeeordersystem.global.response.ApiResponse;
import com.example.coffeeordersystem.menu.dto.MenuListResponse;
import com.example.coffeeordersystem.menu.service.MenuService;

import java.util.List;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/menus")
@RequiredArgsConstructor
public class MenuController {

	private final MenuService menuService;

	@GetMapping
	public ResponseEntity<ApiResponse<List<MenuListResponse>>> getMenus() {
		List<MenuListResponse> response = menuService.getActiveMenus();
		return ResponseEntity.status(HttpStatus.OK).body(ApiResponse.ok(response));
	}
}
