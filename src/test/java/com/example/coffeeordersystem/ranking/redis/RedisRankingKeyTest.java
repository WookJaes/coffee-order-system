package com.example.coffeeordersystem.ranking.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class RedisRankingKeyTest {

	@Test
	void 일자별_랭킹키와_이벤트_중복마커키를_일관되게_생성한다() {
		// given
		LocalDate date = LocalDate.of(2026, 7, 15);

		// when
		String dailyRankingKey = RedisRankingKey.dailyRanking(date);
		String processedEventKey = RedisRankingKey.processedEvent(42L);

		// then
		assertThat(dailyRankingKey).isEqualTo("coffee:ranking:2026-07-15");
		assertThat(processedEventKey).isEqualTo("coffee:ranking:processed:42");
	}
}
