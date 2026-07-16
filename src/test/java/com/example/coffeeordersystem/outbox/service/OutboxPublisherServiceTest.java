package com.example.coffeeordersystem.outbox.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.mockito.ArgumentCaptor;

import com.example.coffeeordersystem.menu.entity.Menu;
import com.example.coffeeordersystem.menu.entity.MenuStatus;
import com.example.coffeeordersystem.menu.repository.MenuRepository;
import com.example.coffeeordersystem.order.dto.OrderCreateRequest;
import com.example.coffeeordersystem.order.entity.OrderEvent;
import com.example.coffeeordersystem.order.entity.OrderEventStatus;
import com.example.coffeeordersystem.order.repository.OrderEventRepository;
import com.example.coffeeordersystem.order.repository.OrderRepository;
import com.example.coffeeordersystem.order.service.OrderService;
import com.example.coffeeordersystem.outbox.config.OutboxPublisherProperties;
import com.example.coffeeordersystem.outbox.dto.OrderPaidEvent;
import com.example.coffeeordersystem.point.entity.Point;
import com.example.coffeeordersystem.point.repository.PointRepository;
import com.example.coffeeordersystem.point.repository.PointHistoryRepository;
import com.example.coffeeordersystem.user.entity.User;
import com.example.coffeeordersystem.user.repository.UserRepository;

import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = "outbox.publisher.processing-timeout=PT1S")
@ActiveProfiles("test")
class OutboxPublisherServiceTest {

	@Autowired
	private OutboxPublisherService outboxPublisherService;

	@Autowired
	private OutboxEventClaimService outboxEventClaimService;

	@Autowired
	private OutboxPublisherProperties properties;

	@Autowired
	private OrderService orderService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private MenuRepository menuRepository;

	@Autowired
	private PointRepository pointRepository;

	@Autowired
	private OrderEventRepository orderEventRepository;

	@Autowired
	private PointHistoryRepository pointHistoryRepository;

	@Autowired
	private OrderRepository orderRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@MockitoBean
	private KafkaTemplate<String, OrderPaidEvent> kafkaTemplate;

	@AfterEach
	void tearDown() {
		orderEventRepository.deleteAll();
		pointHistoryRepository.deleteAll();
		orderRepository.deleteAll();
		pointRepository.deleteAll();
		menuRepository.deleteAll();
		userRepository.deleteAll();
	}

	@Test
	void PENDING_이벤트를_Kafka에_발행한_후_SENT로_변경한다() {
		EventFixture event = createOrderEvent("success");
		doReturn(CompletableFuture.completedFuture(null)).when(kafkaTemplate)
			.send(eq(properties.topic()), eq(event.orderId().toString()), any(OrderPaidEvent.class));

		outboxPublisherService.publishPendingEvents();

		OrderEvent published = orderEventRepository.findById(event.eventId()).orElseThrow();
		assertThat(published.getStatus()).isEqualTo(OrderEventStatus.SENT);
		assertThat(published.getRetryCount()).isZero();
		ArgumentCaptor<OrderPaidEvent> messageCaptor = ArgumentCaptor.forClass(OrderPaidEvent.class);
		verify(kafkaTemplate).send(eq(properties.topic()), eq(event.orderId().toString()), messageCaptor.capture());
		assertThat(messageCaptor.getValue())
			.isEqualTo(new OrderPaidEvent(
				event.eventId(), event.orderId(), event.userId(), event.menuId(), 4_500, event.orderedAt()
			));
	}

	@Test
	void Kafka_발행_실패는_재시도_횟수를_증가시키고_PENDING으로_되돌린다() {
		EventFixture event = createOrderEvent("retry");
		doReturn(CompletableFuture.failedFuture(new IllegalStateException("broker unavailable"))).when(kafkaTemplate)
			.send(eq(properties.topic()), eq(event.orderId().toString()), any(OrderPaidEvent.class));

		outboxPublisherService.publishPendingEvents();

		OrderEvent retried = orderEventRepository.findById(event.eventId()).orElseThrow();
		assertThat(retried.getStatus()).isEqualTo(OrderEventStatus.PENDING);
		assertThat(retried.getRetryCount()).isEqualTo(1);
		assertThat(retried.getNextAttemptAt()).isAfter(retried.getCreatedAt());
		assertThat(retried.getLastError()).contains("broker unavailable");
	}

	@Test
	void 최대_재시도_횟수를_초과하면_FAILED로_변경한다() {
		EventFixture event = createOrderEvent("failed");
		doReturn(CompletableFuture.failedFuture(new IllegalStateException("broker unavailable"))).when(kafkaTemplate)
			.send(eq(properties.topic()), eq(event.orderId().toString()), any(OrderPaidEvent.class));

		for (int attempt = 0; attempt <= properties.maxRetryCount(); attempt++) {
			outboxPublisherService.publishPendingEvents();
		}

		OrderEvent failed = orderEventRepository.findById(event.eventId()).orElseThrow();
		assertThat(failed.getStatus()).isEqualTo(OrderEventStatus.FAILED);
		assertThat(failed.getRetryCount()).isEqualTo(properties.maxRetryCount() + 1);
	}

	@Test
	void 동일_이벤트를_동시에_처리해도_한번만_선점하고_발행한다() throws Exception {
		EventFixture event = createOrderEvent("concurrent");
		CountDownLatch sendStarted = new CountDownLatch(1);
		CountDownLatch releaseSend = new CountDownLatch(1);
		doReturn(CompletableFuture.supplyAsync(() -> {
			sendStarted.countDown();
			try {
				releaseSend.await();
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
			}
			return null;
		})).when(kafkaTemplate)
			.send(eq(properties.topic()), eq(event.orderId().toString()), any(OrderPaidEvent.class));

		ExecutorService executor = Executors.newFixedThreadPool(2);
		Future<?> first = executor.submit(outboxPublisherService::publishPendingEvents);
		sendStarted.await();
		Future<?> second = executor.submit(outboxPublisherService::publishPendingEvents);
		releaseSend.countDown();
		first.get();
		second.get();
		executor.shutdown();

		assertThat(orderEventRepository.findById(event.eventId()).orElseThrow().getStatus()).isEqualTo(OrderEventStatus.SENT);
		verify(kafkaTemplate, times(1)).send(eq(properties.topic()), eq(event.orderId().toString()), any(OrderPaidEvent.class));
	}

	@Test
	void Kafka_발행이_처리_제한_시간을_넘어도_유효한_선점은_다른_Publisher가_회수하지_않는다() throws Exception {
		// given
		EventFixture event = createOrderEvent("long-running-send");
		CountDownLatch sendStarted = new CountDownLatch(1);
		CountDownLatch releaseSend = new CountDownLatch(1);
		CompletableFuture<Void> delayedSend = CompletableFuture.supplyAsync(() -> {
			sendStarted.countDown();
			try {
				releaseSend.await();
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
			}
			return null;
		});
		doAnswer(invocation -> delayedSend).when(kafkaTemplate)
			.send(eq(properties.topic()), eq(event.orderId().toString()), any(OrderPaidEvent.class));

		// when
		ExecutorService executor = Executors.newFixedThreadPool(2);
		Future<?> first = executor.submit(outboxPublisherService::publishPendingEvents);
		sendStarted.await();
		Thread.sleep(properties.processingTimeout().plusMillis(500).toMillis());
		Future<?> second = executor.submit(outboxPublisherService::publishPendingEvents);
		Thread.sleep(200);
		releaseSend.countDown();
		first.get();
		second.get();
		executor.shutdown();

		// then
		assertThat(orderEventRepository.findById(event.eventId()).orElseThrow().getStatus()).isEqualTo(OrderEventStatus.SENT);
		verify(kafkaTemplate, times(1)).send(eq(properties.topic()), eq(event.orderId().toString()), any(OrderPaidEvent.class));
	}

	@Test
	void 오래된_PROCESSING_이벤트를_회복해_다시_발행한다() {
		EventFixture event = createOrderEvent("recovery");
		outboxEventClaimService.claimPendingEvents();
		doReturn(CompletableFuture.completedFuture(null)).when(kafkaTemplate)
			.send(eq(properties.topic()), eq(event.orderId().toString()), any(OrderPaidEvent.class));

		outboxPublisherService.publishPendingEvents();

		assertThat(orderEventRepository.findById(event.eventId()).orElseThrow().getStatus())
			.isEqualTo(OrderEventStatus.PROCESSING);
		verify(kafkaTemplate, times(0)).send(any(), any(), any(OrderPaidEvent.class));

		jdbcTemplate.update(
			"update order_events set processing_started_at = ? where id = ?",
			LocalDateTime.now().minus(properties.processingTimeout()).minusSeconds(1),
			event.eventId()
		);

		outboxPublisherService.publishPendingEvents();

		assertThat(orderEventRepository.findById(event.eventId()).orElseThrow().getStatus()).isEqualTo(OrderEventStatus.SENT);
		verify(kafkaTemplate, times(1)).send(eq(properties.topic()), eq(event.orderId().toString()), any(OrderPaidEvent.class));
	}

	private EventFixture createOrderEvent(String key) {
		User user = userRepository.save(new User("outbox-" + key));
		Menu menu = menuRepository.save(new Menu("outbox coffee", 4_500, MenuStatus.ACTIVE));
		pointRepository.save(new Point(user, 10_000));
		var response = orderService.create(new OrderCreateRequest(user.getId(), menu.getId(), 1), key);
		OrderEvent event = orderEventRepository.findAll().get(0);
		LocalDateTime orderedAt = orderRepository.findById(response.orderId()).orElseThrow().getOrderedAt();
		return new EventFixture(event.getId(), response.orderId(), user.getId(), menu.getId(), orderedAt);
	}

	private record EventFixture(Long eventId, Long orderId, Long userId, Long menuId, LocalDateTime orderedAt) {
	}
}
