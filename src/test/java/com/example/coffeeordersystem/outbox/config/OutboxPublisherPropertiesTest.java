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
			.hasMessage("Outbox 처리 제한 시간은 1초 이상이어야 합니다.");
	}

	@Test
	void 처리_제한_시간은_lease_갱신_주기보다_충분히_길어야_한다() {
		assertThatThrownBy(() -> new OutboxPublisherProperties(
			"order-paid",
			Duration.ofSeconds(5),
			100,
			3,
			Duration.ofSeconds(30),
			Duration.ofMillis(999)
		))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Outbox 처리 제한 시간은 1초 이상이어야 합니다.");
	}
}
