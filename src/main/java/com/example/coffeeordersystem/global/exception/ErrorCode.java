package com.example.coffeeordersystem.global.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

	// point
	INVALID_USER_ID(HttpStatus.BAD_REQUEST, "사용자 ID는 필수입니다."),
	INVALID_CHARGE_AMOUNT(HttpStatus.BAD_REQUEST, "충전 금액은 1 이상 100,000 이하여야 합니다."),
	USER_NOT_FOUND(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."),

	INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다.");

	private final HttpStatus status;
	private final String message;

	ErrorCode(HttpStatus status, String message) {
		this.status = status;
		this.message = message;
	}

	public HttpStatus getStatus() {
		return status;
	}

	public String getMessage() {
		return message;
	}
}
