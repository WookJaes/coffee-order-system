package com.example.coffeeordersystem.global.config.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

class RankingRedisConfigTest {

	@Test
	void Spring이_구성한_RedisTemplate으로_집계_서비스와_Lua_스크립트를_생성한다() {
		// given
		RankingRedisConfig config = new RankingRedisConfig(new RankingRedisProperties(Duration.ofDays(8), Duration.ofMinutes(1), Duration.ofSeconds(20)));
		StringRedisTemplate redisTemplate = org.mockito.Mockito.mock(StringRedisTemplate.class);

		// when
		RedisScript<Long> script = config.rankingProcessOnceScript();
		var aggregationService = config.redisRankingAggregationService(redisTemplate, script);

		// then
		assertThat(aggregationService).isNotNull();
		assertThat(script.getScriptAsString())
			.contains("ZINCRBY", "INCRBY", "KEYS[4]", "KEYS[5]");
	}

	@Test
	void Redis_7일_snapshot_Lua는_상태_count와_ZSET을_함께_읽는다() {
		// given
		RankingRedisConfig config = new RankingRedisConfig(new RankingRedisProperties(Duration.ofDays(8), Duration.ofMinutes(1), Duration.ofSeconds(20)));

		// when
		RedisScript<String> script = config.rankingReadSnapshotScript();

		// then
		assertThat(script.getScriptAsString())
			.contains("GET", "EXISTS", "ZRANGE", "WITHSCORES");
	}

	@Test
	void UUID_토큰이_일치할_때만_잠금_lease를_연장하는_Lua_스크립트를_생성한다() {
		// given
		RankingRedisConfig config = new RankingRedisConfig(new RankingRedisProperties(Duration.ofDays(8), Duration.ofMinutes(1), Duration.ofSeconds(20)));

		// when
		RedisScript<Long> script = config.rankingRenewLockScript();

		// then
		assertThat(script.getScriptAsString()).contains("GET").contains("EXPIRE").contains("ARGV[1]");
	}

	@Test
	void lease_연장_주기는_양수이고_잠금_TTL보다_짧아야_한다() {
		// given
		Duration keyTtl = Duration.ofDays(8);
		Duration lockTtl = Duration.ofMinutes(1);

		// when
		org.assertj.core.api.ThrowableAssert.ThrowingCallable zeroInterval = () ->
			new RankingRedisProperties(keyTtl, lockTtl, Duration.ZERO);
		org.assertj.core.api.ThrowableAssert.ThrowingCallable equalInterval = () ->
			new RankingRedisProperties(keyTtl, lockTtl, lockTtl);
		org.assertj.core.api.ThrowableAssert.ThrowingCallable subSecondTtl = () ->
			new RankingRedisProperties(keyTtl, Duration.ofMillis(500), Duration.ofMillis(100));
		org.assertj.core.api.ThrowableAssert.ThrowingCallable subSecondInterval = () ->
			new RankingRedisProperties(keyTtl, lockTtl, Duration.ofMillis(500));

		// then
		assertThatThrownBy(zeroInterval).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(equalInterval).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(subSecondTtl).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(subSecondInterval).isInstanceOf(IllegalArgumentException.class);
	}
}
