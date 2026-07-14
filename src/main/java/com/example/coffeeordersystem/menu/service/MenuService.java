package com.example.coffeeordersystem.menu.service;

import com.example.coffeeordersystem.menu.dto.MenuListResponse;
import com.example.coffeeordersystem.menu.entity.MenuStatus;
import com.example.coffeeordersystem.menu.repository.MenuRepository;

import java.util.List;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class MenuService {

	private final MenuRepository menuRepository;

	public List<MenuListResponse> getActiveMenus() {
		return menuRepository.findAllByStatus(MenuStatus.ACTIVE).stream()
			.map(MenuListResponse::from)
			.toList();
	}
}
