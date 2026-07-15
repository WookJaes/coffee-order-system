package com.example.coffeeordersystem.global.config.kafka;

import java.time.Duration;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "ranking.consumer")
public record RankingConsumerProperties(
	boolean enabled,
	@NotBlank String topic,
	@NotBlank String groupId,
	@NotBlank String dltTopic,
	@Positive int maxRetryAttempts,
	@NotNull Duration retryBackoff,
	@Positive int concurrency
) {

	public RankingConsumerProperties {
		if (retryBackoff == null || retryBackoff.isNegative()) {
			throw new IllegalArgumentException("랭킹 Consumer 재시도 backoff는 0 이상이어야 합니다.");
		}
	}
}
