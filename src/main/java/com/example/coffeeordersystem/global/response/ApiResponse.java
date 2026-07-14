package com.example.coffeeordersystem.global.response;

import org.springframework.http.HttpStatus;

public record ApiResponse<T>(int status, String message, T data) {

	public static <T> ApiResponse<T> ok(T data) {
		return new ApiResponse<>(HttpStatus.OK.value(), "요청이 성공했습니다.", data);
	}
}
