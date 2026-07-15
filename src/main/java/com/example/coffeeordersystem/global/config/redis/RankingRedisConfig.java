package com.example.coffeeordersystem.global.config.redis;

import com.example.coffeeordersystem.ranking.redis.RedisRankingAggregationService;

import java.time.Clock;
import java.time.ZoneId;

import lombok.RequiredArgsConstructor;

import com.example.coffeeordersystem.order.repository.OrderRepository;
import com.example.coffeeordersystem.ranking.service.PopularMenuRankingService;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

@Configuration
@RequiredArgsConstructor
@EnableConfigurationProperties(RankingRedisProperties.class)
	public class RankingRedisConfig {

	private final RankingRedisProperties properties;

	@Bean
	@ConditionalOnProperty(prefix = "ranking.consumer", name = "enabled", havingValue = "true")
	public RedisRankingAggregationService redisRankingAggregationService(
		StringRedisTemplate redisTemplate,
		RedisScript<Long> rankingProcessOnceScript
	) {
		return new RedisRankingAggregationService(
			redisTemplate,
			properties.keyTtl(),
			Clock.system(ZoneId.of("Asia/Seoul")),
			rankingProcessOnceScript
		);
	}

	@Bean
	@ConditionalOnProperty(prefix = "ranking.consumer", name = "enabled", havingValue = "true")
	public RedisScript<Long> rankingProcessOnceScript() {
		DefaultRedisScript<Long> script = new DefaultRedisScript<>();
		script.setLocation(new ClassPathResource("scripts/ranking-process-once.lua"));
		script.setResultType(Long.class);
		return script;
	}

	@Bean
	public Clock rankingClock() {
		return Clock.system(ZoneId.of("Asia/Seoul"));
	}

	@Bean
	public PopularMenuRankingService popularMenuRankingService(
		StringRedisTemplate redisTemplate,
		OrderRepository orderRepository,
		Clock rankingClock
	) {
		return new PopularMenuRankingService(redisTemplate, orderRepository, properties.keyTtl(), rankingClock);
	}
}
