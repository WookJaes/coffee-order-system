package com.example.coffeeordersystem.global.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

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
}
