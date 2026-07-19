package com.example.coffeeordersystem.global.config.redis;

import com.example.coffeeordersystem.order.repository.OrderEventRepository;
import com.example.coffeeordersystem.order.repository.OrderRepository;
import com.example.coffeeordersystem.ranking.redis.RedisRankingAggregationService;
import com.example.coffeeordersystem.ranking.service.PopularMenuRankingService;

import java.time.Clock;
import java.time.ZoneId;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import lombok.RequiredArgsConstructor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Qualifier;
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
		@Qualifier("rankingProcessOnceScript") RedisScript<Long> rankingProcessOnceScript
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
	public RedisScript<Long> rankingReleaseLockScript() {
		DefaultRedisScript<Long> script = new DefaultRedisScript<>();
		script.setLocation(new ClassPathResource("scripts/ranking-release-lock.lua"));
		script.setResultType(Long.class);
		return script;
	}

	@Bean
	public RedisScript<Long> rankingRenewLockScript() {
		DefaultRedisScript<Long> script = new DefaultRedisScript<>();
		script.setLocation(new ClassPathResource("scripts/ranking-renew-lock.lua"));
		script.setResultType(Long.class);
		return script;
	}

	@Bean
	public RedisScript<Long> rankingCleanupRebuildMarkerScript() {
		DefaultRedisScript<Long> script = new DefaultRedisScript<>();
		script.setLocation(new ClassPathResource("scripts/ranking-cleanup-rebuild-marker.lua"));
		script.setResultType(Long.class);
		return script;
	}

	@Bean
	public RedisScript<Long> rankingCleanupRebuildScript() {
		DefaultRedisScript<Long> script = new DefaultRedisScript<>();
		script.setLocation(new ClassPathResource("scripts/ranking-cleanup-rebuild.lua"));
		script.setResultType(Long.class);
		return script;
	}

	@Bean
	public RedisScript<Long> rankingRebuildWriteScript() {
		DefaultRedisScript<Long> script = new DefaultRedisScript<>();
		script.setLocation(new ClassPathResource("scripts/ranking-rebuild-write.lua"));
		script.setResultType(Long.class);
		return script;
	}

	@Bean
	public RedisScript<String> rankingReadSnapshotScript() {
		DefaultRedisScript<String> script = new DefaultRedisScript<>();
		script.setLocation(new ClassPathResource("scripts/ranking-read-snapshot.lua"));
		script.setResultType(String.class);
		return script;
	}

	@Bean(destroyMethod = "shutdown")
	public ScheduledExecutorService rankingRebuildLeaseScheduler() {
		return Executors.newSingleThreadScheduledExecutor();
	}

	@Bean
	public Clock rankingClock() {
		return Clock.system(ZoneId.of("Asia/Seoul"));
	}

	@Bean
	public PopularMenuRankingService popularMenuRankingService(
		StringRedisTemplate redisTemplate,
		OrderRepository orderRepository,
		OrderEventRepository orderEventRepository,
		@Qualifier("rankingReleaseLockScript") RedisScript<Long> rankingReleaseLockScript,
		@Qualifier("rankingRenewLockScript") RedisScript<Long> rankingRenewLockScript,
		@Qualifier("rankingCleanupRebuildMarkerScript") RedisScript<Long> rankingCleanupRebuildMarkerScript,
		@Qualifier("rankingCleanupRebuildScript") RedisScript<Long> rankingCleanupRebuildScript,
		@Qualifier("rankingRebuildWriteScript") RedisScript<Long> rankingRebuildWriteScript,
		@Qualifier("rankingReadSnapshotScript") RedisScript<String> rankingReadSnapshotScript,
		ScheduledExecutorService rankingRebuildLeaseScheduler,
		Clock rankingClock
	) {
		return new PopularMenuRankingService(
			redisTemplate,
			orderRepository,
			orderEventRepository,
			properties.keyTtl(),
			properties.rebuildLockTtl(),
			properties.rebuildLockRenewInterval(),
			rankingReleaseLockScript,
			rankingRenewLockScript,
			rankingCleanupRebuildMarkerScript,
			rankingCleanupRebuildScript,
			rankingRebuildWriteScript,
			rankingReadSnapshotScript,
			rankingRebuildLeaseScheduler,
			rankingClock
		);
	}
}
