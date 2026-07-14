package com.example.coffeeordersystem.menu.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.coffeeordersystem.menu.entity.Menu;
import com.example.coffeeordersystem.menu.entity.MenuStatus;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MenuRepositoryTest {

	@Autowired
	private MenuRepository menuRepository;

	@Test
	void ACTIVE_상태의_메뉴만_조회한다() {
		// given
		Menu activeMenu = menuRepository.saveAndFlush(new Menu("아메리카노", 4500, MenuStatus.ACTIVE));
		menuRepository.save(new Menu("품절 라떼", 5000, MenuStatus.SOLD_OUT));

		// when
		var activeMenus = menuRepository.findAllByStatus(MenuStatus.ACTIVE);

		// then
		assertThat(activeMenus)
			.extracting(Menu::getName)
			.containsExactly("아메리카노");
		assertThat(activeMenu.getCreatedAt()).isNotNull();
		assertThat(activeMenu.getUpdatedAt()).isNotNull();
	}
}
