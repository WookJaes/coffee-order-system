package com.example.coffeeordersystem.outbox.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.coffeeordersystem.global.exception.BusinessException;
import com.example.coffeeordersystem.global.exception.ErrorCode;
import com.example.coffeeordersystem.outbox.dto.OutboxReprocessResponse;
import com.example.coffeeordersystem.outbox.dto.OutboxStatusCountsResponse;
import com.example.coffeeordersystem.outbox.service.OutboxAdminService;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(OutboxAdminController.class)
class OutboxAdminControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private OutboxAdminService outboxAdminService;

	@MockitoBean
	private JpaMetamodelMappingContext jpaMappingContext;

	@Test
	void FAILED_재처리_성공은_200_공통_응답을_반환한다() throws Exception {
		given(outboxAdminService.reprocessFailedEvent(1L))
			.willReturn(new OutboxReprocessResponse(1L, "PENDING", LocalDateTime.of(2026, 7, 19, 12, 0)));

		mockMvc.perform(post("/api/admin/outbox/events/1/reprocess"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value(HttpStatus.OK.value()))
			.andExpect(jsonPath("$.message").value("요청이 성공했습니다."))
			.andExpect(jsonPath("$.data.eventId").value(1))
			.andExpect(jsonPath("$.data.status").value("PENDING"));
	}

	@Test
	void 존재하지_않는_이벤트_재처리는_404_공통_오류를_반환한다() throws Exception {
		willThrow(new BusinessException(ErrorCode.OUTBOX_EVENT_NOT_FOUND))
			.given(outboxAdminService).reprocessFailedEvent(999L);

		mockMvc.perform(post("/api/admin/outbox/events/999/reprocess"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.status").value(HttpStatus.NOT_FOUND.value()))
			.andExpect(jsonPath("$.message").value("Outbox 이벤트를 찾을 수 없습니다."))
			.andExpect(jsonPath("$.data").doesNotExist());
	}

	@Test
	void FAILED가_아닌_이벤트_재처리는_409_공통_오류를_반환한다() throws Exception {
		willThrow(new BusinessException(ErrorCode.OUTBOX_EVENT_NOT_REPROCESSABLE))
			.given(outboxAdminService).reprocessFailedEvent(1L);

		mockMvc.perform(post("/api/admin/outbox/events/1/reprocess"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.status").value(HttpStatus.CONFLICT.value()))
			.andExpect(jsonPath("$.message").value("FAILED 상태의 Outbox 이벤트만 재처리할 수 있습니다."))
			.andExpect(jsonPath("$.data").doesNotExist());
	}

	@Test
	void 상태별_건수_조회는_200_공통_응답을_반환한다() throws Exception {
		given(outboxAdminService.getStatusCounts()).willReturn(new OutboxStatusCountsResponse(3, 2, 1));

		mockMvc.perform(get("/api/admin/outbox/status-counts"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value(HttpStatus.OK.value()))
			.andExpect(jsonPath("$.data.pending").value(3))
			.andExpect(jsonPath("$.data.processing").value(2))
			.andExpect(jsonPath("$.data.failed").value(1));
	}
}
