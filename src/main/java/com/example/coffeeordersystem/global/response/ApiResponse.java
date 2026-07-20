package com.example.coffeeordersystem.global.response;

import org.springframework.http.HttpStatus;

public record ApiResponse<T>(int status, String message, T data) {

	public static <T> ApiResponse<T> ok(T data) {
		return new ApiResponse<>(HttpStatus.OK.value(), "요청이 성공했습니다.", data);
	}

	public static <T> ApiResponse<T> created(T data) {
		return new ApiResponse<>(HttpStatus.CREATED.value(), "주문 및 결제가 성공적으로 완료되었습니다.", data);
	}
}
