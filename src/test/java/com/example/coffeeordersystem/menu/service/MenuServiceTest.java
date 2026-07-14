package com.example.coffeeordersystem.menu.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.coffeeordersystem.menu.entity.Menu;
import com.example.coffeeordersystem.menu.entity.MenuStatus;
import com.example.coffeeordersystem.menu.repository.MenuRepository;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MenuServiceTest {

	@Mock
	private MenuRepository menuRepository;

	@InjectMocks
	private MenuService menuService;

	@Test
	void 판매중인_메뉴만_반환한다() {
		// given
		Menu activeMenu = new Menu("아메리카노", 4500, MenuStatus.ACTIVE);

		org.mockito.BDDMockito.given(menuRepository.findAllByStatus(MenuStatus.ACTIVE))
			.willReturn(List.of(activeMenu));

		// when
		var activeMenus = menuService.getActiveMenus();

		// then
		assertThat(activeMenus)
			.extracting(menu -> menu.name())
			.containsExactly("아메리카노");

		org.mockito.BDDMockito.then(menuRepository).should().findAllByStatus(MenuStatus.ACTIVE);
	}
}
