package com.example.coffeeordersystem.point.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.coffeeordersystem.global.exception.BusinessException;
import com.example.coffeeordersystem.global.exception.ErrorCode;
import com.example.coffeeordersystem.point.dto.PointChargeResponse;
import com.example.coffeeordersystem.point.service.PointService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PointController.class)
class PointControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private PointService pointService;

	@MockitoBean
	private JpaMetamodelMappingContext jpaMappingContext;

	@Test
	void 포인트_충전_성공은_200_공통_응답을_반환한다() throws Exception {
		// given
		given(pointService.charge(any()))
			.willReturn(new PointChargeResponse(1L, 10_000, 10_000));

		// when & then
		mockMvc.perform(post("/api/points/charge")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"userId\":1,\"amount\":10000}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value(HttpStatus.OK.value()))
			.andExpect(jsonPath("$.message").value("요청이 성공했습니다."))
			.andExpect(jsonPath("$.data.userId").value(1))
			.andExpect(jsonPath("$.data.chargedAmount").value(10_000))
			.andExpect(jsonPath("$.data.balance").value(10_000));
	}

	@Test
	void 잘못된_충전_금액은_400_실패_공통_응답을_반환한다() throws Exception {
		// when & then
		mockMvc.perform(post("/api/points/charge")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"userId\":1,\"amount\":0}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.status").value(HttpStatus.BAD_REQUEST.value()))
			.andExpect(jsonPath("$.message").value("충전 금액은 1 이상이어야 합니다."))
			.andExpect(jsonPath("$.data").doesNotExist());
		verifyNoInteractions(pointService);
	}

	@Test
	void null_충전_금액은_400_실패_공통_응답을_반환한다() throws Exception {
		// when & then
		mockMvc.perform(post("/api/points/charge")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"userId\":1,\"amount\":null}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.status").value(HttpStatus.BAD_REQUEST.value()))
			.andExpect(jsonPath("$.message").value("충전 금액은 필수입니다."))
			.andExpect(jsonPath("$.data").doesNotExist());
		verifyNoInteractions(pointService);
	}

	@Test
	void 숫자가_아닌_충전_금액은_400_실패_공통_응답을_반환한다() throws Exception {
		// when & then
		mockMvc.perform(post("/api/points/charge")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"userId\":1,\"amount\":\"invalid\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.status").value(HttpStatus.BAD_REQUEST.value()))
			.andExpect(jsonPath("$.message").value("요청 본문 형식이 올바르지 않습니다."))
			.andExpect(jsonPath("$.data").doesNotExist());
		verifyNoInteractions(pointService);
	}

	@Test
	void 숫자가_아닌_사용자_ID는_400_실패_공통_응답을_반환한다() throws Exception {
		// when & then
		mockMvc.perform(post("/api/points/charge")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"userId\":\"invalid\",\"amount\":10000}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.status").value(HttpStatus.BAD_REQUEST.value()))
			.andExpect(jsonPath("$.message").value("요청 본문 형식이 올바르지 않습니다."))
			.andExpect(jsonPath("$.data").doesNotExist());
		verifyNoInteractions(pointService);
	}

	@Test
	void 문법이_잘못된_JSON은_400_실패_공통_응답을_반환한다() throws Exception {
		// when & then
		mockMvc.perform(post("/api/points/charge")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.status").value(HttpStatus.BAD_REQUEST.value()))
			.andExpect(jsonPath("$.message").value("요청 본문 형식이 올바르지 않습니다."))
			.andExpect(jsonPath("$.data").doesNotExist());
		verifyNoInteractions(pointService);
	}

	@Test
	void 최대_충전_금액을_초과하면_400_실패_공통_응답을_반환한다() throws Exception {
		// when & then
		mockMvc.perform(post("/api/points/charge")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"userId\":1,\"amount\":100001}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.status").value(HttpStatus.BAD_REQUEST.value()))
			.andExpect(jsonPath("$.message").value("충전 금액은 100,000 이하여야 합니다."))
			.andExpect(jsonPath("$.data").doesNotExist());
		verifyNoInteractions(pointService);
	}

	@Test
	void null_사용자_ID는_400_실패_공통_응답을_반환한다() throws Exception {
		// when & then
		mockMvc.perform(post("/api/points/charge")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"userId\":null,\"amount\":10000}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.status").value(HttpStatus.BAD_REQUEST.value()))
			.andExpect(jsonPath("$.message").value("사용자 ID는 필수입니다."))
			.andExpect(jsonPath("$.data").doesNotExist());
		verifyNoInteractions(pointService);
	}

	@Test
	void 존재하지_않는_사용자는_404_실패_공통_응답을_반환한다() throws Exception {
		// given
		willThrow(new BusinessException(ErrorCode.USER_NOT_FOUND))
			.given(pointService).charge(any());

		// when & then
		mockMvc.perform(post("/api/points/charge")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"userId\":999,\"amount\":10000}"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.status").value(HttpStatus.NOT_FOUND.value()))
			.andExpect(jsonPath("$.message").value("사용자를 찾을 수 없습니다."))
			.andExpect(jsonPath("$.data").doesNotExist());
	}
}
