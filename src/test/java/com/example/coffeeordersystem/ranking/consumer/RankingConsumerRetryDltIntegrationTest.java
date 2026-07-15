package com.example.coffeeordersystem.ranking.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.example.coffeeordersystem.outbox.dto.OrderPaidEvent;
import com.example.coffeeordersystem.ranking.redis.RedisRankingAggregationService;

import java.time.Duration;
import java.util.Map;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {
	"ranking.consumer.enabled=true",
	"ranking.consumer.topic=order-paid",
	"ranking.consumer.group-id=product-ranking-group-test",
	"ranking.consumer.dlt-topic=order-paid.DLT",
	"ranking.consumer.max-retry-attempts=2",
	"ranking.consumer.retry-backoff=PT0.1S",
	"ranking.consumer.concurrency=1",
	"ranking.redis.key-ttl=PT192H"
})
@EmbeddedKafka(
	partitions = 1,
	topics = {"order-paid", "order-paid.DLT"},
	bootstrapServersProperty = "spring.kafka.bootstrap-servers"
)
class RankingConsumerRetryDltIntegrationTest {

	@Autowired
	private KafkaTemplate<String, OrderPaidEvent> kafkaTemplate;

	@Autowired
	private EmbeddedKafkaBroker embeddedKafkaBroker;

	@MockitoBean
	private RedisRankingAggregationService aggregationService;

	@AfterEach
	void resetMock() {
		org.mockito.Mockito.reset(aggregationService);
	}

	@Test
	void Redis_실패는_설정된_횟수만큼_재시도한_뒤_DLT로_보낸다() throws Exception {
		// given
		OrderPaidEvent event = new OrderPaidEvent(42L, 10L, 3L, 7L, 4_500);
		doThrow(new IllegalStateException("redis unavailable")).when(aggregationService).aggregate(event);
		try (Consumer<String, OrderPaidEvent> dltConsumer = dltConsumer()) {
			embeddedKafkaBroker.consumeFromAnEmbeddedTopic(dltConsumer, "order-paid.DLT");

			// when
			kafkaTemplate.send("order-paid", event.orderId().toString(), event).get();

			var records = KafkaTestUtils.getRecords(dltConsumer, Duration.ofSeconds(15));

			// then
			assertThat(records.records("order-paid.DLT")).singleElement()
				.extracting(record -> record.value())
				.isEqualTo(event);
			verify(aggregationService, timeout(10_000).times(3)).aggregate(event);
		}
	}

	private Consumer<String, OrderPaidEvent> dltConsumer() {
		Map<String, Object> properties = KafkaTestUtils.consumerProps(embeddedKafkaBroker, "ranking-dlt-observer", false);
		properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
		JacksonJsonDeserializer<OrderPaidEvent> valueDeserializer = new JacksonJsonDeserializer<>(OrderPaidEvent.class);
		valueDeserializer.addTrustedPackages("com.example.coffeeordersystem.outbox.dto");
		return new DefaultKafkaConsumerFactory<>(properties, new StringDeserializer(), valueDeserializer).createConsumer();
	}
}
