package com.example.coffeeordersystem.global.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;

class GlobalExceptionHandlerTest {

	private final GlobalExceptionHandler exceptionHandler = new GlobalExceptionHandler();

	@Test
	void 멱등성_키_유니크_제약_위반은_409로_변환한다() {
		// given
		DataIntegrityViolationException exception = new DataIntegrityViolationException(
			"duplicate key",
			new RuntimeException("uk_orders_user_id_idempotency_key")
		);

		// when
		var response = exceptionHandler.handleDataIntegrityViolationException(exception);

		// then
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(response.getBody()).hasFieldOrPropertyWithValue("status", HttpStatus.CONFLICT.value());
	}

	@Test
	void 멱등성_키와_무관한_무결성_예외는_500으로_변환한다() {
		// given
		DataIntegrityViolationException exception = new DataIntegrityViolationException(
			"check constraint violation",
			new RuntimeException("chk_orders_quantity_positive")
		);

		// when
		var response = exceptionHandler.handleDataIntegrityViolationException(exception);

		// then
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
		assertThat(response.getBody()).hasFieldOrPropertyWithValue("status", HttpStatus.INTERNAL_SERVER_ERROR.value());
	}

	@Test
	void 지원하지_않는_HTTP_메서드는_405_공통_오류_응답으로_변환한다() {
		// given
		HttpRequestMethodNotSupportedException exception = new HttpRequestMethodNotSupportedException("GET");

		// when
		var response = exceptionHandler.handleHttpRequestMethodNotSupportedException(
			exception
		);

		// then
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
		assertThat(response.getBody()).hasFieldOrPropertyWithValue("status", HttpStatus.METHOD_NOT_ALLOWED.value());
		assertThat(response.getBody()).hasFieldOrPropertyWithValue("message", "지원하지 않는 HTTP 메서드입니다.");
	}

	@Test
	void 지원하지_않는_Content_Type은_415_공통_오류_응답으로_변환한다() {
		// given
		HttpMediaTypeNotSupportedException exception = new HttpMediaTypeNotSupportedException("text/plain");

		// when
		var response = exceptionHandler.handleHttpMediaTypeNotSupportedException(
			exception
		);

		// then
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
		assertThat(response.getBody()).hasFieldOrPropertyWithValue("status", HttpStatus.UNSUPPORTED_MEDIA_TYPE.value());
		assertThat(response.getBody()).hasFieldOrPropertyWithValue("message", "지원하지 않는 Content-Type입니다.");
	}

	@Test
	void 예상하지_못한_예외는_내부_상세_없이_500_공통_오류_응답으로_변환한다() {
		// given
		IllegalStateException exception = new IllegalStateException("internal exception detail");

		// when
		var response = exceptionHandler.handleException(exception);

		// then
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
		assertThat(response.getBody()).hasFieldOrPropertyWithValue("status", HttpStatus.INTERNAL_SERVER_ERROR.value());
		assertThat(response.getBody()).hasFieldOrPropertyWithValue("message", "서버 오류가 발생했습니다.");
	}
}
