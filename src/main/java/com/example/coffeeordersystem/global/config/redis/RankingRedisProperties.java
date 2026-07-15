package com.example.coffeeordersystem.global.config.redis;

import java.time.Duration;

import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "ranking.redis")
public record RankingRedisProperties(
	@NotNull Duration keyTtl,
	@NotNull Duration rebuildLockTtl
) {

	public RankingRedisProperties {
		if (keyTtl == null || keyTtl.isZero() || keyTtl.isNegative()) {
			throw new IllegalArgumentException("랭킹 Redis 키 TTL은 0보다 커야 합니다.");
		}
		if (rebuildLockTtl == null || rebuildLockTtl.isZero() || rebuildLockTtl.isNegative()) {
			throw new IllegalArgumentException("랭킹 Redis 재구성 잠금 TTL은 0보다 커야 합니다.");
		}
	}
}
