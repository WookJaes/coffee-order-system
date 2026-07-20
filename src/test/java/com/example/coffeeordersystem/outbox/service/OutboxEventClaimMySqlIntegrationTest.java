package com.example.coffeeordersystem.outbox.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.coffeeordersystem.menu.entity.Menu;
import com.example.coffeeordersystem.menu.entity.MenuStatus;
import com.example.coffeeordersystem.menu.repository.MenuRepository;
import com.example.coffeeordersystem.order.entity.Order;
import com.example.coffeeordersystem.order.entity.OrderEvent;
import com.example.coffeeordersystem.order.entity.OrderEventStatus;
import com.example.coffeeordersystem.order.repository.OrderEventRepository;
import com.example.coffeeordersystem.order.repository.OrderRepository;
import com.example.coffeeordersystem.outbox.dto.ClaimedOrderEvent;
import com.example.coffeeordersystem.user.entity.User;
import com.example.coffeeordersystem.user.repository.UserRepository;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import jakarta.persistence.EntityManagerFactory;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
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

@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class OutboxEventClaimMySqlIntegrationTest {

	@Container
	private static final MySQLContainer<?> mysql = new MySQLContainer<>(DockerImageName.parse("mysql:8.4.5"))
		.withEnv("MYSQL_ROOT_HOST", "%");

	@Autowired private OutboxEventClaimService claimService;
	@Autowired private OrderEventRepository orderEventRepository;
	@Autowired private OrderRepository orderRepository;
	@Autowired private MenuRepository menuRepository;
	@Autowired private UserRepository userRepository;
	@Autowired private EntityManagerFactory entityManagerFactory;

	@DynamicPropertySource
	static void mysqlProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", mysql::getJdbcUrl);
		registry.add("spring.datasource.username", mysql::getUsername);
		registry.add("spring.datasource.password", mysql::getPassword);
		registry.add("spring.datasource.driver-class-name", mysql::getDriverClassName);
	}

	@AfterEach
	void cleanDatabase() {
		orderEventRepository.deleteAll();
		orderRepository.deleteAll();
		menuRepository.deleteAll();
		userRepository.deleteAll();
	}

	@Test
	void 실제_MySQL에서_배치_선점은_이벤트_수와_무관하게_고정된_SQL만_사용하고_Lazy_조회가_없다() {
		createPendingEvents(5);
		Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
		statistics.clear();

		List<ClaimedOrderEvent> claimedEvents = claimService.claimPendingEvents();

		assertThat(claimedEvents).hasSize(5);
		assertThat(claimedEvents).extracting(event -> event.message().orderId()).doesNotHaveDuplicates();
		assertThat(statistics.getEntityFetchCount()).isZero();
		assertThat(statistics.getPrepareStatementCount()).isLessThanOrEqualTo(4);
	}

	@Test
	void 실제_MySQL에서_동시_Publisher는_같은_이벤트를_한번만_배치_선점한다() throws Exception {
		createPendingEvents(10);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);

		Future<List<ClaimedOrderEvent>> first = executor.submit(() -> claimTogether(ready, start));
		Future<List<ClaimedOrderEvent>> second = executor.submit(() -> claimTogether(ready, start));
		ready.await();
		start.countDown();
		List<ClaimedOrderEvent> firstClaims = first.get();
		List<ClaimedOrderEvent> secondClaims = second.get();
		executor.shutdown();

		Set<Long> firstIds = eventIds(firstClaims);
		Set<Long> secondIds = eventIds(secondClaims);
		Set<Long> allIds = new HashSet<>(firstIds);
		allIds.addAll(secondIds);
		assertThat(firstIds.stream().noneMatch(secondIds::contains)).isTrue();
		assertThat(allIds).hasSize(10);
		assertThat(orderEventRepository.findAll())
			.allSatisfy(event -> assertThat(event.getStatus()).isEqualTo(OrderEventStatus.PROCESSING));
	}

	private List<ClaimedOrderEvent> claimTogether(CountDownLatch ready, CountDownLatch start) throws InterruptedException {
		ready.countDown();
		start.await();
		return claimService.claimPendingEvents();
	}

	private Set<Long> eventIds(List<ClaimedOrderEvent> claims) {
		return claims.stream().map(claim -> claim.message().eventId()).collect(Collectors.toSet());
	}

	private void createPendingEvents(int count) {
		for (int index = 0; index < count; index++) {
			User user = userRepository.saveAndFlush(new User("outbox-claim-user-" + index));
			Menu menu = menuRepository.saveAndFlush(new Menu("outbox-claim-menu-" + index, 4_500, MenuStatus.ACTIVE));
			Order order = orderRepository.saveAndFlush(new Order(user, menu, "outbox-claim-" + index, 1, 4_500));
			orderEventRepository.saveAndFlush(new OrderEvent(order));
		}
	}
}
