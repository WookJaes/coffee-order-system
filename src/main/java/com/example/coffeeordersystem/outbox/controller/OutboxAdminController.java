package com.example.coffeeordersystem.outbox.controller;

import com.example.coffeeordersystem.global.response.ApiResponse;
import com.example.coffeeordersystem.outbox.dto.OutboxReprocessResponse;
import com.example.coffeeordersystem.outbox.dto.OutboxStatusCountsResponse;
import com.example.coffeeordersystem.outbox.service.OutboxAdminService;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/outbox")
@RequiredArgsConstructor
public class OutboxAdminController {

	private final OutboxAdminService outboxAdminService;

	@PostMapping("/events/{eventId}/reprocess")
	public ResponseEntity<ApiResponse<OutboxReprocessResponse>> reprocessFailedEvent(@PathVariable Long eventId) {
		return ResponseEntity.status(HttpStatus.OK).body(ApiResponse.ok(outboxAdminService.reprocessFailedEvent(eventId)));
	}

	@GetMapping("/status-counts")
	public ResponseEntity<ApiResponse<OutboxStatusCountsResponse>> getStatusCounts() {
		return ResponseEntity.status(HttpStatus.OK).body(ApiResponse.ok(outboxAdminService.getStatusCounts()));
	}
}
