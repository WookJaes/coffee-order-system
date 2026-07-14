package com.example.coffeeordersystem.point.controller;

import com.example.coffeeordersystem.global.response.ApiResponse;
import com.example.coffeeordersystem.point.dto.PointChargeRequest;
import com.example.coffeeordersystem.point.dto.PointChargeResponse;
import com.example.coffeeordersystem.point.service.PointService;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/points")
@RequiredArgsConstructor
public class PointController {

	private final PointService pointService;

	@PostMapping("/charge")
	public ResponseEntity<ApiResponse<PointChargeResponse>> charge(
		@Valid @RequestBody PointChargeRequest request
	) {
		PointChargeResponse response = pointService.charge(request);
		return ResponseEntity.status(HttpStatus.OK).body(ApiResponse.ok(response));
	}
}
