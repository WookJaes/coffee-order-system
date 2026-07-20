package com.example.coffeeordersystem.outbox.scheduler;

import com.example.coffeeordersystem.outbox.service.OutboxPublisherService;

import lombok.RequiredArgsConstructor;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OutboxPublisherScheduler {

	private final OutboxPublisherService outboxPublisherService;

	@Scheduled(fixedDelayString = "${outbox.publisher.fixed-delay}")
	public void publishPendingEvents() {
		outboxPublisherService.publishPendingEvents();
	}
}
