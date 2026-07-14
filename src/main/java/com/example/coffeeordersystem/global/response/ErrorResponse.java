package com.example.coffeeordersystem.global.response;

import org.springframework.http.HttpStatus;

public record ErrorResponse(int status, String message) {

	public static ErrorResponse of(HttpStatus status, String message) {
		return new ErrorResponse(status.value(), message);
	}
}
