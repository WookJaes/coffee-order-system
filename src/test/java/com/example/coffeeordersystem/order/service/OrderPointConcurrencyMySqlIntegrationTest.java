package com.example.coffeeordersystem.order.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.coffeeordersystem.global.exception.BusinessException;
import com.example.coffeeordersystem.menu.entity.Menu;
import com.example.coffeeordersystem.menu.entity.MenuStatus;
import com.example.coffeeordersystem.menu.repository.MenuRepository;
import com.example.coffeeordersystem.order.dto.OrderCreateRequest;
import com.example.coffeeordersystem.order.dto.OrderCreateResponse;
import com.example.coffeeordersystem.order.repository.OrderEventRepository;
import com.example.coffeeordersystem.order.repository.OrderRepository;
import com.example.coffeeordersystem.point.dto.PointChargeRequest;
import com.example.coffeeordersystem.point.entity.Point;
import com.example.coffeeordersystem.point.entity.PointHistoryType;
import com.example.coffeeordersystem.point.repository.PointHistoryRepository;
import com.example.coffeeordersystem.point.repository.PointRepository;
import com.example.coffeeordersystem.point.service.PointService;
import com.example.coffeeordersystem.user.entity.User;
import com.example.coffeeordersystem.user.repository.UserRepository;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class OrderPointConcurrencyMySqlIntegrationTest {

	@Container
	private static final MySQLContainer<?> mysql = new MySQLContainer<>(DockerImageName.parse("mysql:8.4.5"))
		.withEnv("MYSQL_ROOT_HOST", "%");

	@Autowired private OrderService orderService;
	@Autowired private PointService pointService;
	@Autowired private UserRepository userRepository;
	@Autowired private MenuRepository menuRepository;
	@Autowired private PointRepository pointRepository;
	@Autowired private PointHistoryRepository pointHistoryRepository;
	@Autowired private OrderRepository orderRepository;
	@Autowired private OrderEventRepository orderEventRepository;

	@DynamicPropertySource
	static void mysqlProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", mysql::getJdbcUrl);
		registry.add("spring.datasource.username", mysql::getUsername);
		registry.add("spring.datasource.password", mysql::getPassword);
		registry.add("spring.datasource.driver-class-name", mysql::getDriverClassName);
	}

	@BeforeEach
	void cleanDatabase() {
		orderEventRepository.deleteAll();
		pointHistoryRepository.deleteAll();
		orderRepository.deleteAll();
		pointRepository.deleteAll();
		menuRepository.deleteAll();
		userRepository.deleteAll();
	}

	@Test
	void 실제_MySQL에서_동일_사용자_동시_주문은_잔액_USE_주문_Outbox를_정합하게_저장한다() throws Exception {
		// given
		User user = userRepository.saveAndFlush(new User("mysql 동시 주문 사용자"));
		Menu menu = menuRepository.saveAndFlush(new Menu("아메리카노", 4_500, MenuStatus.ACTIVE));
		pointRepository.saveAndFlush(new Point(user, 100_000));

		// when
		List<Throwable> failures = runConcurrentlyAfterInitialReadsWaitForUserLock(user.getId(), 5, index ->
			orderService.create(new OrderCreateRequest(user.getId(), menu.getId(), 1), "mysql-order-" + index)
		);

		// then
		assertThat(failures).isEmpty();
		assertThat(pointRepository.findByUserId(user.getId()).orElseThrow().getBalance()).isEqualTo(77_500);
		assertThat(pointHistoryRepository.findAll()).hasSize(5)
			.allSatisfy(history -> {
				assertThat(history.getType()).isEqualTo(PointHistoryType.USE);
				assertThat(history.getAmount()).isEqualTo(4_500);
			});
		assertThat(orderRepository.count()).isEqualTo(5);
		assertThat(orderEventRepository.count()).isEqualTo(5);
	}

	@Test
	void 실제_MySQL에서_충전과_주문이_교차해도_잔액_산식과_이력이_정합하다() throws Exception {
		// given
		User user = userRepository.saveAndFlush(new User("mysql 교차 사용자"));
		Menu menu = menuRepository.saveAndFlush(new Menu("아메리카노", 4_500, MenuStatus.ACTIVE));
		pointRepository.saveAndFlush(new Point(user, 50_000));

		// when
		List<Throwable> failures = runConcurrently(10, index -> {
			if (index % 2 == 0) {
				pointService.charge(new PointChargeRequest(user.getId(), 1_000));
				return;
			}
			orderService.create(new OrderCreateRequest(user.getId(), menu.getId(), 1), "mysql-cross-" + index);
		});

		// then
		assertThat(failures).isEmpty();
		int chargeTotal = pointHistoryRepository.findAll().stream()
			.filter(history -> history.getType() == PointHistoryType.CHARGE)
			.mapToInt(history -> history.getAmount()).sum();
		int useTotal = pointHistoryRepository.findAll().stream()
			.filter(history -> history.getType() == PointHistoryType.USE)
			.mapToInt(history -> history.getAmount()).sum();
		assertThat(pointRepository.findByUserId(user.getId()).orElseThrow().getBalance())
			.isEqualTo(50_000 + chargeTotal - useTotal).isGreaterThanOrEqualTo(0);
		assertThat(orderRepository.count()).isEqualTo(5);
		assertThat(pointHistoryRepository.findAll().stream()
			.filter(history -> history.getType() == PointHistoryType.USE)).hasSize(5);
		assertThat(orderEventRepository.count()).isEqualTo(5);
	}

	@Test
	void 실제_MySQL에서_동일_멱등성_키_동시_재시도는_한번만_차감한다() throws Exception {
		// given
		User user = userRepository.saveAndFlush(new User("mysql 멱등 사용자"));
		Menu menu = menuRepository.saveAndFlush(new Menu("아메리카노", 4_500, MenuStatus.ACTIVE));
		pointRepository.saveAndFlush(new Point(user, 100_000));
		List<OrderCreateResponse> responses = new CopyOnWriteArrayList<>();

		// when
		List<Throwable> failures = runConcurrentlyAfterInitialReadsWaitForUserLock(user.getId(), 5, ignored ->
			responses.add(orderService.create(new OrderCreateRequest(user.getId(), menu.getId(), 1), "mysql-same-key"))
		);

		// then
		assertThat(failures).isEmpty();
		assertThat(pointRepository.findByUserId(user.getId()).orElseThrow().getBalance()).isEqualTo(95_500);
		assertThat(orderRepository.count()).isEqualTo(1);
		assertThat(pointHistoryRepository.count()).isEqualTo(1);
		assertThat(orderEventRepository.count()).isEqualTo(1);
		assertThat(responses).hasSize(5)
			.extracting(OrderCreateResponse::orderId).containsOnly(responses.get(0).orderId());
		assertThat(responses)
			.extracting(OrderCreateResponse::remainingPoint).containsOnly(95_500);
	}

	@Test
	void 실제_MySQL에서_잔액_부족_동시_주문은_데이터를_남기지_않는다() throws Exception {
		// given
		User user = userRepository.saveAndFlush(new User("mysql 잔액 부족 사용자"));
		Menu menu = menuRepository.saveAndFlush(new Menu("아메리카노", 4_500, MenuStatus.ACTIVE));
		pointRepository.saveAndFlush(new Point(user, 4_000));

		// when
		List<Throwable> failures = runConcurrently(5, index ->
			orderService.create(new OrderCreateRequest(user.getId(), menu.getId(), 1), "mysql-insufficient-" + index)
		);

		// then
		assertThat(failures).hasSize(5).allSatisfy(failure -> assertThat(failure).isInstanceOf(BusinessException.class));
		assertThat(pointRepository.findByUserId(user.getId()).orElseThrow().getBalance()).isEqualTo(4_000);
		assertThat(orderRepository.count()).isZero();
		assertThat(pointHistoryRepository.count()).isZero();
		assertThat(orderEventRepository.count()).isZero();
	}

	private List<Throwable> runConcurrently(int count, ConcurrentRequest request) throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(count);
		CountDownLatch ready = new CountDownLatch(count);
		CountDownLatch start = new CountDownLatch(1);
		try {
			List<Future<Throwable>> futures = submitConcurrentRequests(executor, count, request, ready, start);
			ready.await();
			start.countDown();
			return collectFailures(futures);
		} finally {
			executor.shutdownNow();
		}
	}

	private List<Throwable> runConcurrentlyAfterInitialReadsWaitForUserLock(
		Long userId,
		int count,
		ConcurrentRequest request
	) throws Exception {
		try (Connection lockConnection = DriverManager.getConnection(mysql.getJdbcUrl(), "root", mysql.getPassword())) {
			lockConnection.setAutoCommit(false);
			try (PreparedStatement statement = lockConnection.prepareStatement("select id from users where id = ? for update")) {
				statement.setLong(1, userId);
				statement.executeQuery();
			}

			ExecutorService executor = Executors.newFixedThreadPool(count);
			CountDownLatch ready = new CountDownLatch(count);
			CountDownLatch start = new CountDownLatch(1);
			try {
				List<Future<Throwable>> futures = submitConcurrentRequests(executor, count, request, ready, start);
				ready.await();
				start.countDown();
				awaitUserLockWaits(lockConnection, count);
				lockConnection.commit();
				return collectFailures(futures);
			} finally {
				executor.shutdownNow();
			}
		}
	}

	private void awaitUserLockWaits(Connection connection, int expectedWaitCount) throws Exception {
		long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
		while (System.nanoTime() < deadline) {
			try (PreparedStatement statement = connection.prepareStatement(
				"select count(*) from performance_schema.data_lock_waits"
			); ResultSet resultSet = statement.executeQuery()) {
				resultSet.next();
				if (resultSet.getInt(1) >= expectedWaitCount) {
					return;
				}
			}
			Thread.sleep(10);
		}
		throw new AssertionError("모든 동시 주문이 사용자 행 잠금을 대기하지 않았습니다.");
	}

	private List<Future<Throwable>> submitConcurrentRequests(
		ExecutorService executor,
		int count,
		ConcurrentRequest request,
		CountDownLatch ready,
		CountDownLatch start
	) {
		List<Future<Throwable>> futures = new ArrayList<>();
		for (int index = 0; index < count; index++) {
			int requestIndex = index;
			futures.add(executor.submit(() -> {
				ready.countDown();
				start.await();
				try {
					request.execute(requestIndex);
					return null;
				} catch (Throwable throwable) {
					return throwable;
				}
			}));
		}
		return futures;
	}

	private List<Throwable> collectFailures(List<Future<Throwable>> futures) throws Exception {
		List<Throwable> failures = new ArrayList<>();
		for (Future<Throwable> future : futures) {
			Throwable failure = future.get();
			if (failure != null) failures.add(failure);
		}
		return failures;
	}

	@FunctionalInterface
	private interface ConcurrentRequest {
		void execute(int index);
	}
}
