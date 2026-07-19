package com.example.coffeeordersystem.global.config.dataplatform;

import java.time.Duration;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "data-platform")
public record DataPlatformProperties(
	@NotBlank String apiUrl,
	@NotNull Duration connectTimeout,
	@NotNull Duration readTimeout
) {

	public DataPlatformProperties {
		if (connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero()) {
			throw new IllegalArgumentException("데이터 플랫폼 연결 timeout은 0보다 커야 합니다.");
		}
		if (readTimeout == null || readTimeout.isNegative() || readTimeout.isZero()) {
			throw new IllegalArgumentException("데이터 플랫폼 응답 timeout은 0보다 커야 합니다.");
		}
	}
}
