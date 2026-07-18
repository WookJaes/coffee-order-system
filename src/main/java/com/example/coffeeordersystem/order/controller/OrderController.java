package com.example.coffeeordersystem.order.controller;

import com.example.coffeeordersystem.global.response.ApiResponse;
import com.example.coffeeordersystem.global.exception.BusinessException;
import com.example.coffeeordersystem.global.exception.ErrorCode;
import com.example.coffeeordersystem.order.dto.OrderCreateRequest;
import com.example.coffeeordersystem.order.dto.OrderCreateResponse;
import com.example.coffeeordersystem.order.service.OrderService;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

	private static final int IDEMPOTENCY_KEY_MAX_LENGTH = 100;

	private final OrderService orderService;

	@PostMapping
	public ResponseEntity<ApiResponse<OrderCreateResponse>> create(
		@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
		@Valid @RequestBody OrderCreateRequest request
	) {
		validateIdempotencyKeyLength(idempotencyKey);
		OrderCreateResponse response = orderService.create(request, idempotencyKey);
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(response));
	}

	private void validateIdempotencyKeyLength(String idempotencyKey) {
		if (idempotencyKey != null && idempotencyKey.length() > IDEMPOTENCY_KEY_MAX_LENGTH) {
			throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_TOO_LONG);
		}
	}
}
