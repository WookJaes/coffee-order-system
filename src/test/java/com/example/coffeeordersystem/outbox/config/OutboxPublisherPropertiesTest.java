package com.example.coffeeordersystem.outbox.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class OutboxPublisherPropertiesTest {

	@Test
	void 처리_제한_시간은_양수여야_한다() {
		assertThatThrownBy(() -> new OutboxPublisherProperties(
			"order-paid",
			Duration.ofSeconds(5),
			100,
			3,
			Duration.ofSeconds(30),
			Duration.ZERO
		))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Outbox 처리 제한 시간은 0보다 커야 합니다.");
	}
}
