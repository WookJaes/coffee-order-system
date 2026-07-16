package com.example.coffeeordersystem.outbox.config;

import java.time.Duration;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "outbox.publisher")
public record OutboxPublisherProperties(
	@NotBlank String topic,
	@NotNull Duration fixedDelay,
	@Positive int batchSize,
	@Positive int maxRetryCount,
	@NotNull Duration retryBackoff,
	@NotNull Duration processingTimeout
) {
	private static final Duration MIN_PROCESSING_TIMEOUT = Duration.ofSeconds(1);

	public OutboxPublisherProperties {
		if (processingTimeout == null || processingTimeout.compareTo(MIN_PROCESSING_TIMEOUT) < 0) {
			throw new IllegalArgumentException("Outbox 처리 제한 시간은 1초 이상이어야 합니다.");
		}
	}
}
