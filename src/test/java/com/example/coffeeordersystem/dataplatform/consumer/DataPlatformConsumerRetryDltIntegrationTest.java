package com.example.coffeeordersystem.dataplatform.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.example.coffeeordersystem.outbox.dto.OrderPaidEvent;
import com.example.coffeeordersystem.ranking.redis.RedisRankingAggregationService;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {
	"data-platform.consumer.enabled=true",
	"data-platform.consumer.topic=order-paid",
	"data-platform.consumer.group-id=data-platform-group-test",
	"data-platform.consumer.dlt-topic=order-paid.data-platform.DLT",
	"data-platform.consumer.max-retry-attempts=2",
	"data-platform.consumer.retry-backoff=PT0.1S",
	"data-platform.consumer.concurrency=1",
	"data-platform.connect-timeout=PT0.1S",
	"data-platform.read-timeout=PT0.1S",
	"ranking.consumer.enabled=true",
	"ranking.consumer.topic=order-paid",
	"ranking.consumer.group-id=product-ranking-group-test",
	"ranking.consumer.dlt-topic=order-paid.ranking.DLT",
	"ranking.consumer.max-retry-attempts=2",
	"ranking.consumer.retry-backoff=PT0.1S",
	"ranking.consumer.concurrency=1",
	"ranking.redis.key-ttl=PT192H"
})
@EmbeddedKafka(
	partitions = 1,
	topics = {"order-paid", "order-paid.data-platform.DLT", "order-paid.ranking.DLT"},
	bootstrapServersProperty = "spring.kafka.bootstrap-servers"
)
class DataPlatformConsumerRetryDltIntegrationTest {

	private static final AtomicInteger status = new AtomicInteger(204);
	private static final AtomicInteger delayMillis = new AtomicInteger();
	private static final AtomicInteger requests = new AtomicInteger();
	private static final Map<String, String> logicalCollections = new ConcurrentHashMap<>();
	private static final Map<String, AtomicInteger> requestsByKey = new ConcurrentHashMap<>();
	private static HttpServer server;

	@Autowired
	private KafkaTemplate<String, OrderPaidEvent> kafkaTemplate;

	@Autowired
	private EmbeddedKafkaBroker embeddedKafkaBroker;

	@Autowired
	private KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry;

	@MockitoBean
	private RedisRankingAggregationService aggregationService;

	@DynamicPropertySource
	static void dataPlatformUrl(DynamicPropertyRegistry registry) {
		server = startServer();
		registry.add("data-platform.api-url", () -> "http://localhost:" + server.getAddress().getPort() + "/order-paid");
	}

	@AfterAll
	static void stopServer() {
		server.stop(0);
	}

	@BeforeEach
	void waitForConsumerAssignments() {
		kafkaListenerEndpointRegistry.getListenerContainers().forEach(container ->
			ContainerTestUtils.waitForAssignment(container, embeddedKafkaBroker.getPartitionsPerTopic())
		);
	}

	@AfterEach
	void resetServer() {
		status.set(204);
		delayMillis.set(0);
		requests.set(0);
		logicalCollections.clear();
		requestsByKey.clear();
		org.mockito.Mockito.reset(aggregationService);
	}

	@Test
	void 동일_이벤트를_재소비해도_Mock_플랫폼은_한건으로_수집한다() throws Exception {
		// given
		OrderPaidEvent event = event(42L);

		// when
		kafkaTemplate.send("order-paid", event.orderId().toString(), event).get();
		kafkaTemplate.send("order-paid", event.orderId().toString(), event).get();
		awaitRequests(2);

		// then
		assertThat(logicalCollections).containsEntry("order-paid:42", "{\"eventId\":42,\"userId\":3,\"menuId\":7,\"paymentAmount\":4500}");
		assertThat(requestsByKey.get("order-paid:42")).hasValue(2);
	}

	@Test
	void 오백대_응답은_세번_호출한_뒤_전용_DLT로_보낸다() throws Exception {
		// given
		status.set(500);
		OrderPaidEvent event = event(43L);
		try (Consumer<String, OrderPaidEvent> dltConsumer = dltConsumer()) {
			embeddedKafkaBroker.consumeFromAnEmbeddedTopic(dltConsumer, "order-paid.data-platform.DLT");

			// when
			kafkaTemplate.send("order-paid", event.orderId().toString(), event).get();
			ConsumerRecord<String, OrderPaidEvent> dltRecord = awaitDltEvent(dltConsumer, event);

			// then
			assertThat(dltRecord.value()).isEqualTo(event);
			assertThat(requestsByKey.get("order-paid:43")).hasValue(3);
		}
	}

	@Test
	void 사백대_응답은_재시도하지_않고_전용_DLT로_보낸다() throws Exception {
		// given
		status.set(400);
		OrderPaidEvent event = event(44L);
		try (Consumer<String, OrderPaidEvent> dltConsumer = dltConsumer()) {
			embeddedKafkaBroker.consumeFromAnEmbeddedTopic(dltConsumer, "order-paid.data-platform.DLT");

			// when
			kafkaTemplate.send("order-paid", event.orderId().toString(), event).get();
			ConsumerRecord<String, OrderPaidEvent> dltRecord = awaitDltEvent(dltConsumer, event);

			// then
			assertThat(dltRecord.value()).isEqualTo(event);
			assertThat(requestsByKey.get("order-paid:44")).hasValue(1);
		}
	}

	@Test
	void timeout은_세번_호출한_뒤_전용_DLT로_보낸다() throws Exception {
		// given
		delayMillis.set(300);
		OrderPaidEvent event = event(45L);
		try (Consumer<String, OrderPaidEvent> dltConsumer = dltConsumer()) {
			embeddedKafkaBroker.consumeFromAnEmbeddedTopic(dltConsumer, "order-paid.data-platform.DLT");

			// when
			kafkaTemplate.send("order-paid", event.orderId().toString(), event).get();
			ConsumerRecord<String, OrderPaidEvent> dltRecord = awaitDltEvent(dltConsumer, event);

			// then
			assertThat(dltRecord.value()).isEqualTo(event);
			assertThat(requestsByKey.get("order-paid:45")).hasValue(3);
		}
	}

	@Test
	void 데이터플랫폼_실패가_랭킹_consumer_처리를_막지_않는다() throws Exception {
		// given
		status.set(400);
		OrderPaidEvent event = event(46L);

		// when
		kafkaTemplate.send("order-paid", event.orderId().toString(), event).get();
		awaitRequests(1);

		// then
		verify(aggregationService, timeout(10_000)).aggregate(event);
	}

	private static HttpServer startServer() {
		try {
			HttpServer httpServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
			httpServer.setExecutor(Executors.newCachedThreadPool());
			httpServer.createContext("/order-paid", DataPlatformConsumerRetryDltIntegrationTest::handle);
			httpServer.start();
			return httpServer;
		} catch (IOException exception) {
			throw new IllegalStateException("Mock 데이터 플랫폼 서버를 시작할 수 없습니다.", exception);
		}
	}

	private static void handle(HttpExchange exchange) throws IOException {
		requests.incrementAndGet();
		String key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
		String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
		logicalCollections.putIfAbsent(key, body);
		requestsByKey.computeIfAbsent(key, ignored -> new AtomicInteger()).incrementAndGet();
		try {
			Thread.sleep(delayMillis.get());
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
		}
		exchange.sendResponseHeaders(status.get(), -1);
		exchange.close();
	}

	private void awaitRequests(int expected) {
		long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
		while (requests.get() < expected && System.nanoTime() < deadline) {
			Thread.onSpinWait();
		}
		assertThat(requests.get()).isGreaterThanOrEqualTo(expected);
	}

	private OrderPaidEvent event(long eventId) {
		return new OrderPaidEvent(eventId, eventId + 100, 3L, 7L, 4_500, LocalDateTime.of(2026, 7, 19, 10, 0));
	}

	private Consumer<String, OrderPaidEvent> dltConsumer() {
		Map<String, Object> properties = KafkaTestUtils.consumerProps(embeddedKafkaBroker, "data-platform-dlt-observer-" + System.nanoTime(), false);
		properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
		JacksonJsonDeserializer<OrderPaidEvent> valueDeserializer = new JacksonJsonDeserializer<>(OrderPaidEvent.class);
		valueDeserializer.addTrustedPackages("com.example.coffeeordersystem.outbox.dto");
		return new DefaultKafkaConsumerFactory<>(properties, new StringDeserializer(), valueDeserializer).createConsumer();
	}

	private ConsumerRecord<String, OrderPaidEvent> awaitDltEvent(
		Consumer<String, OrderPaidEvent> consumer,
		OrderPaidEvent expected
	) {
		long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
		while (System.nanoTime() < deadline) {
			for (ConsumerRecord<String, OrderPaidEvent> record : consumer.poll(Duration.ofSeconds(1))) {
				if (record.value().equals(expected)) {
					return record;
				}
			}
		}
		throw new AssertionError("전용 DLT에서 원본 이벤트를 찾지 못했습니다: " + expected.eventId());
	}
}
