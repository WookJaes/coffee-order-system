package com.example.coffeeordersystem.global.config.kafka;

import com.example.coffeeordersystem.outbox.dto.OrderPaidEvent;

import lombok.RequiredArgsConstructor;

import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
@RequiredArgsConstructor
@EnableConfigurationProperties(RankingConsumerProperties.class)
@ConditionalOnProperty(prefix = "ranking.consumer", name = "enabled", havingValue = "true")
public class RankingKafkaConsumerConfig {

	private final RankingConsumerProperties properties;

	@Bean
	public ConcurrentKafkaListenerContainerFactory<String, OrderPaidEvent> orderPaidRankingKafkaListenerContainerFactory(
		ConsumerFactory<String, OrderPaidEvent> consumerFactory,
		KafkaTemplate<String, OrderPaidEvent> kafkaTemplate
	) {
		DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
			kafkaTemplate,
			(record, exception) -> new TopicPartition(properties.dltTopic(), record.partition())
		);
		DefaultErrorHandler errorHandler = new DefaultErrorHandler(
			recoverer,
			new FixedBackOff(properties.retryBackoff().toMillis(), properties.maxRetryAttempts())
		);
		errorHandler.setCommitRecovered(true);

		ConcurrentKafkaListenerContainerFactory<String, OrderPaidEvent> factory =
			new ConcurrentKafkaListenerContainerFactory<>();
		factory.setConsumerFactory(consumerFactory);
		factory.setConcurrency(properties.concurrency());
		factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
		factory.setCommonErrorHandler(errorHandler);
		return factory;
	}
}
