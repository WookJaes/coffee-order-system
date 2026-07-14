package com.example.coffeeordersystem.global.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

	// point
	INVALID_USER_ID(HttpStatus.BAD_REQUEST, "사용자 ID는 필수입니다."),
	INVALID_CHARGE_AMOUNT(HttpStatus.BAD_REQUEST, "충전 금액은 1 이상 100,000 이하여야 합니다."),
	USER_NOT_FOUND(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."),
	INSUFFICIENT_POINT(HttpStatus.BAD_REQUEST, "포인트가 부족합니다."),

	// order
	INVALID_MENU_ID(HttpStatus.BAD_REQUEST, "메뉴 ID는 필수입니다."),
	INVALID_ORDER_QUANTITY(HttpStatus.BAD_REQUEST, "수량은 1 이상이어야 합니다."),
	MENU_NOT_FOUND(HttpStatus.NOT_FOUND, "메뉴를 찾을 수 없습니다."),
	MENU_NOT_ON_SALE(HttpStatus.BAD_REQUEST, "판매 중인 메뉴가 아닙니다."),
	POINT_NOT_FOUND(HttpStatus.NOT_FOUND, "포인트 정보를 찾을 수 없습니다."),
	IDEMPOTENCY_KEY_REQUIRED(HttpStatus.BAD_REQUEST, "Idempotency-Key 헤더는 필수입니다."),
	IDEMPOTENCY_KEY_CONFLICT(HttpStatus.CONFLICT, "동일한 멱등성 키로 다른 메뉴를 주문할 수 없습니다."),

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
