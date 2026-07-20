package com.example.coffeeordersystem.menu.service;

import com.example.coffeeordersystem.menu.dto.MenuListResponse;
import com.example.coffeeordersystem.menu.dto.PopularMenuResponse;
import com.example.coffeeordersystem.menu.entity.Menu;
import com.example.coffeeordersystem.menu.entity.MenuStatus;
import com.example.coffeeordersystem.menu.repository.MenuRepository;
import com.example.coffeeordersystem.ranking.dto.PopularMenuRanking;
import com.example.coffeeordersystem.ranking.service.PopularMenuRankingService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.function.Function;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class MenuService {

	private final MenuRepository menuRepository;
	private final PopularMenuRankingService popularMenuRankingService;

	public List<MenuListResponse> getActiveMenus() {
		return menuRepository.findAllByStatus(MenuStatus.ACTIVE).stream()
			.map(MenuListResponse::from)
			.toList();
	}

	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public List<PopularMenuResponse> getPopularMenus() {
		List<PopularMenuRanking> rankings = popularMenuRankingService.getPopularMenuRankings();
		Map<Long, Menu> activeMenusById = menuRepository
			.findAllByIdInAndStatus(rankings.stream().map(PopularMenuRanking::menuId).toList(), MenuStatus.ACTIVE)
			.stream()
			.collect(Collectors.toMap(
				Menu::getId,
				Function.identity()
			));

		List<PopularMenuResponse> responses = new ArrayList<>();
		int rank = 1;
		for (PopularMenuRanking ranking : rankings) {
			Menu activeMenu = activeMenusById.get(ranking.menuId());
			if (activeMenu == null) {
				continue;
			}
			responses.add(PopularMenuResponse.from(rank++, activeMenu, ranking));
			if (responses.size() == 3) {
				break;
			}
		}
		return responses;
	}
}
