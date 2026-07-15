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
}
