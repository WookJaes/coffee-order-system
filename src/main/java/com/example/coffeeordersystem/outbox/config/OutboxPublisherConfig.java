package com.example.coffeeordersystem.outbox.config;

import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class OutboxPublisherConfig {

	@Bean(name = "outboxKafkaSendExecutor", destroyMethod = "shutdown")
	public ThreadPoolTaskExecutor outboxKafkaSendExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(1);
		executor.setMaxPoolSize(1);
		executor.setQueueCapacity(0);
		executor.setThreadNamePrefix("outbox-kafka-send-");
		executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
		executor.initialize();
		return executor;
	}
}
