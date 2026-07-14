package com.example.coffeeordersystem.order.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.coffeeordersystem.global.exception.BusinessException;
import com.example.coffeeordersystem.global.exception.ErrorCode;
import com.example.coffeeordersystem.order.dto.OrderCreateResponse;
import com.example.coffeeordersystem.order.service.OrderService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@WebMvcTest(OrderController.class)
class OrderControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private OrderService orderService;

	@MockitoBean
	private JpaMetamodelMappingContext jpaMappingContext;

	@Test
	void 주문_결제_성공은_201_성공_공통_응답을_반환한다() throws Exception {
		// given
		given(orderService.create(any(), eq("order-key")))
			.willReturn(new OrderCreateResponse(1L, 1L, 1L, 2, 9_000, 1_000, "PAID"));

		// when
		ResultActions response = mockMvc.perform(post("/api/orders")
				.header("Idempotency-Key", "order-key")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"userId\":1,\"menuId\":1,\"quantity\":2}"));

		// then
		response
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value(HttpStatus.CREATED.value()))
			.andExpect(jsonPath("$.message").value("주문 및 결제가 성공적으로 완료되었습니다."))
			.andExpect(jsonPath("$.data.orderId").value(1))
			.andExpect(jsonPath("$.data.quantity").value(2))
			.andExpect(jsonPath("$.data.paymentAmount").value(9_000))
			.andExpect(jsonPath("$.data.remainingPoint").value(1_000))
			.andExpect(jsonPath("$.data.status").value("PAID"));
	}

	@Test
	void 멱등성_키가_없거나_공백이면_400_실패_공통_응답을_반환한다() throws Exception {
		// given: Idempotency-Key 헤더가 없거나 공백이다.
		willThrow(new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED))
			.given(orderService).create(any(), isNull());
		willThrow(new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED))
			.given(orderService).create(any(), eq(" "));

		// when
		ResultActions missingHeaderResponse = mockMvc.perform(post("/api/orders")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"userId\":1,\"menuId\":1,\"quantity\":1}"));
		ResultActions blankHeaderResponse = mockMvc.perform(post("/api/orders")
				.header("Idempotency-Key", " ")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"userId\":1,\"menuId\":1,\"quantity\":1}"));

		// then
		missingHeaderResponse
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.status").value(HttpStatus.BAD_REQUEST.value()))
			.andExpect(jsonPath("$.message").value("Idempotency-Key 헤더는 필수입니다."))
			.andExpect(jsonPath("$.data").doesNotExist());
		blankHeaderResponse
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.status").value(HttpStatus.BAD_REQUEST.value()))
			.andExpect(jsonPath("$.data").doesNotExist());

	}

	@Test
	void 수량이_1_미만이면_400_실패_공통_응답을_반환한다() throws Exception {
		// given: 요청 수량이 0이다.

		// when
		ResultActions response = mockMvc.perform(post("/api/orders")
				.header("Idempotency-Key", "invalid-quantity-key")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"userId\":1,\"menuId\":1,\"quantity\":0}"));

		// then
		response
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.status").value(HttpStatus.BAD_REQUEST.value()))
			.andExpect(jsonPath("$.message").value("수량은 1 이상이어야 합니다."))
			.andExpect(jsonPath("$.data").doesNotExist());
	}

	@Test
	void 같은_키로_다른_메뉴를_요청하면_409_실패_공통_응답을_반환한다() throws Exception {
		// given
		willThrow(new BusinessException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT))
			.given(orderService).create(any(), eq("conflict-key"));

		// when
		ResultActions response = mockMvc.perform(post("/api/orders")
				.header("Idempotency-Key", "conflict-key")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"userId\":1,\"menuId\":2,\"quantity\":1}"));

		// then
		response
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.status").value(HttpStatus.CONFLICT.value()))
			.andExpect(jsonPath("$.message").value("동일한 멱등성 키로 다른 메뉴를 주문할 수 없습니다."))
			.andExpect(jsonPath("$.data").doesNotExist());
	}
}
