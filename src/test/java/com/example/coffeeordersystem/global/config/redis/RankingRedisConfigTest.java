package com.example.coffeeordersystem.global.config.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

class RankingRedisConfigTest {

	@Test
	void Spring이_구성한_RedisTemplate으로_집계_서비스와_Lua_스크립트를_생성한다() {
		// given
		RankingRedisConfig config = new RankingRedisConfig(new RankingRedisProperties(Duration.ofDays(8), Duration.ofMinutes(1)));
		StringRedisTemplate redisTemplate = org.mockito.Mockito.mock(StringRedisTemplate.class);

		// when
		RedisScript<Long> script = config.rankingProcessOnceScript();
		var aggregationService = config.redisRankingAggregationService(redisTemplate, script);

		// then
		assertThat(aggregationService).isNotNull();
		assertThat(script.getScriptAsString()).contains("ZINCRBY");
	}
}
