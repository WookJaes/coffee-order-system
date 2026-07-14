package com.example.coffeeordersystem.menu.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.coffeeordersystem.menu.dto.MenuListResponse;
import com.example.coffeeordersystem.menu.service.MenuService;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@WebMvcTest(MenuController.class)
class MenuControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private MenuService menuService;

	@MockitoBean
	private JpaMetamodelMappingContext jpaMappingContext;

	@Test
	void 메뉴_목록을_공통_성공_응답으로_반환한다() throws Exception {
		// given
		org.mockito.BDDMockito.given(menuService.getActiveMenus())
			.willReturn(List.of(new MenuListResponse(1L, "아메리카노", 4500)));

		// when
		ResultActions result = mockMvc.perform(get("/api/menus"));

		// then
		result
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value(200))
			.andExpect(jsonPath("$.message").value("요청이 성공했습니다."))
			.andExpect(jsonPath("$.data[0].menuId").value(1))
			.andExpect(jsonPath("$.data[0].name").value("아메리카노"))
			.andExpect(jsonPath("$.data[0].price").value(4500));
	}
}
