package com.example.coffeeordersystem.outbox.dto;

public record OutboxStatusCountsResponse(long pending, long processing, long failed) {
}
