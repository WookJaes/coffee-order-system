package com.example.coffeeordersystem.outbox.dto;

import java.time.LocalDateTime;

public record OutboxReprocessResponse(Long eventId, String status, LocalDateTime nextAttemptAt) {
}
