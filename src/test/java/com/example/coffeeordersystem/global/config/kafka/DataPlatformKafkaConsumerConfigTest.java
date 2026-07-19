package com.example.coffeeordersystem.global.config.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.example.coffeeordersystem.outbox.dto.OrderPaidEvent;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;

class DataPlatformKafkaConsumerConfigTest {

	@Test
	void 데이터플랫폼_Consumer는_독립_group과_RECORD_ack을_사용한다() {
		// given
		DataPlatformConsumerProperties properties = new DataPlatformConsumerProperties(
			true, "order-paid", "data-platform-group", "order-paid.data-platform.DLT", 2,
			Duration.ofMillis(100), 1
		);
		DataPlatformKafkaConsumerConfig config = new DataPlatformKafkaConsumerConfig(properties);
		@SuppressWarnings("unchecked")
		ConsumerFactory<String, OrderPaidEvent> consumerFactory = org.mockito.Mockito.mock(ConsumerFactory.class);
		@SuppressWarnings("unchecked")
		KafkaTemplate<String, OrderPaidEvent> kafkaTemplate = org.mockito.Mockito.mock(KafkaTemplate.class);

		// when
		var factory = config.orderPaidDataPlatformKafkaListenerContainerFactory(consumerFactory, kafkaTemplate);

		// then
		assertThat(factory.getContainerProperties().getAckMode()).isEqualTo(ContainerProperties.AckMode.RECORD);
		assertThat(factory.createContainer("order-paid").getConcurrency()).isEqualTo(1);
	}

	@Test
	void DLT_발행_실패는_recoverer에서_전파되어_원본_성공처리를_막는다() {
		// given
		DataPlatformConsumerProperties properties = new DataPlatformConsumerProperties(
			true, "order-paid", "data-platform-group", "order-paid.data-platform.DLT", 1,
			Duration.ZERO, 1
		);
		DataPlatformKafkaConsumerConfig config = new DataPlatformKafkaConsumerConfig(properties);
		@SuppressWarnings("unchecked")
		KafkaTemplate<String, OrderPaidEvent> kafkaTemplate = org.mockito.Mockito.mock(KafkaTemplate.class);
		when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(
			CompletableFuture.failedFuture(new IllegalStateException("DLT broker unavailable"))
		);
		DefaultErrorHandler errorHandler = config.dataPlatformErrorHandler(kafkaTemplate);
		ConsumerRecord<String, OrderPaidEvent> record = new ConsumerRecord<>(
			"order-paid", 0, 0L, "10", new OrderPaidEvent(42L, 10L, 3L, 7L, 4_500, null)
		);

		// when
		errorHandler.handleOne(new IllegalStateException("HTTP unavailable"), record, null, null);
		boolean handled = errorHandler.handleOne(new IllegalStateException("HTTP unavailable"), record, null, null);

		// then
		assertThat(handled).isFalse();
		verify(kafkaTemplate).send(any(ProducerRecord.class));
	}
}
