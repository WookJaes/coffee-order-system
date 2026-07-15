package com.example.coffeeordersystem.global.config.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.coffeeordersystem.outbox.dto.OrderPaidEvent;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;

class RankingKafkaConsumerConfigTest {

	@Test
	void 성공한_리스너_처리_뒤에만_offset을_기록하도록_RECORD_ack을_사용한다() {
		// given
		RankingConsumerProperties properties = new RankingConsumerProperties(
			true, "order-paid", "product-ranking-group", "order-paid.DLT", 2,
			Duration.ofSeconds(1), 3
		);
		RankingKafkaConsumerConfig config = new RankingKafkaConsumerConfig(properties);
		@SuppressWarnings("unchecked")
		ConsumerFactory<String, OrderPaidEvent> consumerFactory = org.mockito.Mockito.mock(ConsumerFactory.class);
		@SuppressWarnings("unchecked")
		KafkaTemplate<String, OrderPaidEvent> kafkaTemplate = org.mockito.Mockito.mock(KafkaTemplate.class);

		// when
		var factory = config.orderPaidRankingKafkaListenerContainerFactory(consumerFactory, kafkaTemplate);

		// then
		assertThat(factory.getContainerProperties().getAckMode()).isEqualTo(ContainerProperties.AckMode.RECORD);
	}

	@Test
	void 파티션_세개를_병렬_처리하도록_Consumer_동시성을_세개로_설정한다() {
		// given
		RankingConsumerProperties properties = new RankingConsumerProperties(
			true, "order-paid", "product-ranking-group", "order-paid.DLT", 2,
			Duration.ofSeconds(1), 3
		);
		RankingKafkaConsumerConfig config = new RankingKafkaConsumerConfig(properties);
		@SuppressWarnings("unchecked")
		ConsumerFactory<String, OrderPaidEvent> consumerFactory = org.mockito.Mockito.mock(ConsumerFactory.class);
		@SuppressWarnings("unchecked")
		KafkaTemplate<String, OrderPaidEvent> kafkaTemplate = org.mockito.Mockito.mock(KafkaTemplate.class);

		// when
		var factory = config.orderPaidRankingKafkaListenerContainerFactory(consumerFactory, kafkaTemplate);

		// then
		assertThat(factory.createContainer("order-paid").getConcurrency()).isEqualTo(3);
	}

}
