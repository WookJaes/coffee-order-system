package com.example.coffeeordersystem.global;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.coffeeordersystem.global.response.ApiResponse;
import com.example.coffeeordersystem.global.response.ErrorResponse;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ApiResponseTest {

	@Test
	void 성공_응답은_200과_기본_메시지와_data를_포함한다() {
		// given & when
		ApiResponse<String> response = ApiResponse.ok("결과");

		// then
		assertThat(response.status()).isEqualTo(200);
		assertThat(response.message()).isEqualTo("요청이 성공했습니다.");
		assertThat(response.data()).isEqualTo("결과");
	}

	@Test
	void 실패_응답에는_data를_포함하지_않는다() throws Exception {
		// given & when
		ErrorResponse response = ErrorResponse.of(HttpStatus.BAD_REQUEST, "잘못된 요청입니다.");

		// then
		assertThat(response.status()).isEqualTo(400);
		assertThat(response.message()).isEqualTo("잘못된 요청입니다.");
		assertThat(ErrorResponse.class.getRecordComponents())
			.extracting(component -> component.getName())
			.containsExactly("status", "message");
	}
}
