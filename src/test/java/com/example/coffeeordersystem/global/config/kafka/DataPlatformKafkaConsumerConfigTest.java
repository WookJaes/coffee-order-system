package com.example.coffeeordersystem.global.config.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;

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
		@SuppressWarnings("unchecked")
		KafkaTemplate<String, OrderPaidEvent> kafkaTemplate = org.mockito.Mockito.mock(KafkaTemplate.class);
		when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(
			CompletableFuture.failedFuture(new IllegalStateException("DLT broker unavailable"))
		);
		DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate);
		recoverer.setFailIfSendResultIsError(true);
		ConsumerRecord<String, OrderPaidEvent> record = new ConsumerRecord<>(
			"order-paid", 0, 0L, "10", new OrderPaidEvent(42L, 10L, 3L, 7L, 4_500, null)
		);

		// when
		var action = (org.assertj.core.api.ThrowableAssert.ThrowingCallable) () ->
			recoverer.accept(record, null, new IllegalStateException("HTTP unavailable"));

		// then
		assertThatThrownBy(action).isInstanceOf(RuntimeException.class);
	}
}
