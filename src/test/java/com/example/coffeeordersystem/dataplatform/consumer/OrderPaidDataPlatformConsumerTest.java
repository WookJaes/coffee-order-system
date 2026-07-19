package com.example.coffeeordersystem.dataplatform.consumer;

import static org.mockito.Mockito.verify;

import com.example.coffeeordersystem.dataplatform.client.DataPlatformClient;
import com.example.coffeeordersystem.dataplatform.client.DataPlatformOrderPaidRequest;
import com.example.coffeeordersystem.outbox.dto.OrderPaidEvent;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

class OrderPaidDataPlatformConsumerTest {

	@Test
	void 주문완료_이벤트의_전송필드를_데이터플랫폼에_전달한다() {
		// given
		DataPlatformClient client = org.mockito.Mockito.mock(DataPlatformClient.class);
		OrderPaidDataPlatformConsumer consumer = new OrderPaidDataPlatformConsumer(client);
		OrderPaidEvent event = new OrderPaidEvent(42L, 10L, 3L, 7L, 4_500, LocalDateTime.of(2026, 7, 19, 10, 0));

		// when
		consumer.consume(event);

		// then
		verify(client).send(new DataPlatformOrderPaidRequest(42L, 3L, 7L, 4_500));
	}
}
