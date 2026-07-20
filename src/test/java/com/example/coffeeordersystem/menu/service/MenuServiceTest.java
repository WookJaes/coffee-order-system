package com.example.coffeeordersystem.menu.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.coffeeordersystem.menu.entity.Menu;
import com.example.coffeeordersystem.menu.entity.MenuStatus;
import com.example.coffeeordersystem.menu.dto.PopularMenuResponse;
import com.example.coffeeordersystem.menu.repository.MenuRepository;
import com.example.coffeeordersystem.ranking.dto.PopularMenuRanking;
import com.example.coffeeordersystem.ranking.service.PopularMenuRankingService;

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

	@Mock
	private PopularMenuRankingService popularMenuRankingService;

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

	@Test
	void 인기_랭킹에서_ACTIVE_메뉴만_순위를_유지해_최대_3건_반환한다() {
		// given
		Menu activeMenuThree = new Menu("카페라떼", 5000, MenuStatus.ACTIVE);
		Menu activeMenuOne = new Menu("아메리카노", 4500, MenuStatus.ACTIVE);
		Menu activeMenuFour = new Menu("콜드브루", 5500, MenuStatus.ACTIVE);
		setId(activeMenuThree, 3L);
		setId(activeMenuOne, 1L);
		setId(activeMenuFour, 4L);
		org.mockito.BDDMockito.given(popularMenuRankingService.getPopularMenuRankings())
			.willReturn(List.of(
				new PopularMenuRanking(1L, 10L),
				new PopularMenuRanking(2L, 9L),
				new PopularMenuRanking(3L, 8L),
				new PopularMenuRanking(4L, 7L)
			));
		org.mockito.BDDMockito.given(menuRepository.findAllByIdInAndStatus(
			List.of(1L, 2L, 3L, 4L), MenuStatus.ACTIVE
		)).willReturn(List.of(activeMenuThree, activeMenuOne, activeMenuFour));

		// when
		var popularMenus = menuService.getPopularMenus();

		// then
		assertThat(popularMenus).containsExactly(
			new PopularMenuResponse(1, 1L, "아메리카노", 10L),
			new PopularMenuResponse(2, 3L, "카페라떼", 8L),
			new PopularMenuResponse(3, 4L, "콜드브루", 7L)
		);
	}

	private void setId(Menu menu, Long id) {
		try {
			java.lang.reflect.Field field = Menu.class.getDeclaredField("id");
			field.setAccessible(true);
			field.set(menu, id);
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}
}
