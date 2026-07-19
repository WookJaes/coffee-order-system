package com.example.coffeeordersystem.global.config.kafka;

import java.time.Duration;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "data-platform.consumer")
public record DataPlatformConsumerProperties(
	boolean enabled,
	@NotBlank String topic,
	@NotBlank String groupId,
	@NotBlank String dltTopic,
	@Positive int maxRetryAttempts,
	@NotNull Duration retryBackoff,
	@Positive int concurrency
) {

	public DataPlatformConsumerProperties {
		if (retryBackoff == null || retryBackoff.isNegative()) {
			throw new IllegalArgumentException("데이터 플랫폼 Consumer 재시도 backoff는 0 이상이어야 합니다.");
		}
	}
}
