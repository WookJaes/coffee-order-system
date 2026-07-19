package com.example.coffeeordersystem.dataplatform.consumer;

import com.example.coffeeordersystem.dataplatform.client.DataPlatformClient;
import com.example.coffeeordersystem.dataplatform.client.DataPlatformOrderPaidRequest;
import com.example.coffeeordersystem.outbox.dto.OrderPaidEvent;

import lombok.RequiredArgsConstructor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "data-platform.consumer", name = "enabled", havingValue = "true")
public class OrderPaidDataPlatformConsumer {

	private final DataPlatformClient dataPlatformClient;

	@KafkaListener(
		topics = "${data-platform.consumer.topic}",
		groupId = "${data-platform.consumer.group-id}",
		containerFactory = "orderPaidDataPlatformKafkaListenerContainerFactory"
	)
	public void consume(OrderPaidEvent event) {
		dataPlatformClient.send(new DataPlatformOrderPaidRequest(
			event.eventId(), event.userId(), event.menuId(), event.paymentAmount()
		));
	}
}
