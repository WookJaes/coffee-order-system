package com.example.coffeeordersystem.ranking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.coffeeordersystem.menu.entity.Menu;
import com.example.coffeeordersystem.menu.entity.MenuStatus;
import com.example.coffeeordersystem.menu.repository.MenuRepository;
import com.example.coffeeordersystem.order.entity.Order;
import com.example.coffeeordersystem.order.entity.OrderEvent;
import com.example.coffeeordersystem.order.repository.OrderEventRepository;
import com.example.coffeeordersystem.order.repository.OrderRepository;
import com.example.coffeeordersystem.ranking.dto.PopularMenuRanking;
import com.example.coffeeordersystem.ranking.dto.RebuildOrderEvent;
import com.example.coffeeordersystem.user.entity.User;
import com.example.coffeeordersystem.user.repository.UserRepository;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import jakarta.persistence.EntityManager;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class PopularMenuRankingTransactionIsolationIntegrationTest {

	@Container
	private static final MySQLContainer<?> mysql = new MySQLContainer<>(DockerImageName.parse("mysql:8.4.5"))
		.withEnv("MYSQL_ROOT_HOST", "%");

	@DynamicPropertySource
	static void mysqlProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", mysql::getJdbcUrl);
		registry.add("spring.datasource.username", mysql::getUsername);
		registry.add("spring.datasource.password", mysql::getPassword);
		registry.add("spring.datasource.driver-class-name", mysql::getDriverClassName);
	}

	@Autowired private PopularMenuRankingService popularMenuRankingService;
	@Autowired private UserRepository userRepository;
	@Autowired private MenuRepository menuRepository;
	@Autowired private OrderRepository orderRepository;
	@Autowired private PlatformTransactionManager transactionManager;
	@Autowired private EntityManager entityManager;

	@MockitoSpyBean private OrderEventRepository orderEventRepository;
	@MockitoBean private StringRedisTemplate redisTemplate;

	private final TransactionTemplate externalTransaction = new TransactionTemplate();
	private final TransactionTemplate committedTransaction = new TransactionTemplate();
	private final ValueOperations<String, String> valueOperations = org.mockito.Mockito.mock(ValueOperations.class);
	private final ZSetOperations<String, String> zSetOperations = org.mockito.Mockito.mock(ZSetOperations.class);

	@BeforeEach
	void setUp() {
		externalTransaction.setTransactionManager(transactionManager);
		externalTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
		committedTransaction.setTransactionManager(transactionManager);
		committedTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		orderEventRepository.deleteAll();
		orderRepository.deleteAll();
		menuRepository.deleteAll();
		userRepository.deleteAll();
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
		when(redisTemplate.hasKey(any())).thenReturn(false);
		when(valueOperations.setIfAbsent(any(), any(), any(Duration.class))).thenReturn(true);
		when(zSetOperations.rangeWithScores(any(), any(Long.class), any(Long.class))).thenReturn(java.util.Set.of());
		when(redisTemplate.execute(any(), any(), any(Object[].class))).thenReturn(1L);
	}

	@Test
	void 외부_트랜잭션에서도_재구성_집계와_marker는_동일한_Repeatable_Read_snapshot을_사용한다() {
		// given
		User user = userRepository.saveAndFlush(new User("랭킹 snapshot 사용자"));
		Menu menu = menuRepository.saveAndFlush(new Menu("아메리카노", 4_500, MenuStatus.ACTIVE));
		Long initialEventId = savePaidOrderAndEvent(user.getId(), menu.getId(), "initial-order");
		doAnswer(invocation -> {
			committedTransaction.executeWithoutResult(status ->
				savePaidOrderAndEvent(user.getId(), menu.getId(), "committed-between-queries")
			);
			return entityManager.createQuery("""
				select new com.example.coffeeordersystem.ranking.dto.RebuildOrderEvent(e.id)
				from OrderEvent e
				where e.order.status = com.example.coffeeordersystem.order.entity.OrderStatus.PAID
				  and e.order.orderedAt >= :start
				  and e.order.orderedAt < :end
				""", RebuildOrderEvent.class)
				.setParameter("start", invocation.getArgument(0, LocalDateTime.class))
				.setParameter("end", invocation.getArgument(1, LocalDateTime.class))
				.getResultList();
		}).when(orderEventRepository).findPaidEventsForRankingRebuild(any(), any());

		// when
		List<PopularMenuRanking> rankings = externalTransaction.execute(status ->
			popularMenuRankingService.getPopularMenuRankings()
		);

		// then
		assertThat(rankings).containsExactly(new PopularMenuRanking(menu.getId(), 1L));
		ArgumentCaptor<List<String>> rebuildKeys = listCaptor();
		verify(redisTemplate, org.mockito.Mockito.atLeastOnce()).execute(any(), rebuildKeys.capture(), any(Object[].class));
		assertThat(rebuildKeys.getAllValues())
			.filteredOn(keys -> keys.size() == 2 && keys.get(1).startsWith("coffee:ranking:processed:"))
			.extracting(keys -> keys.get(1))
			.containsExactly("coffee:ranking:processed:" + initialEventId);
	}

	private Long savePaidOrderAndEvent(Long userId, Long menuId, String idempotencyKey) {
		User user = userRepository.getReferenceById(userId);
		Menu menu = menuRepository.getReferenceById(menuId);
		Order order = orderRepository.saveAndFlush(new Order(user, menu, idempotencyKey, 1, 4_500));
		return orderEventRepository.saveAndFlush(new OrderEvent(order)).getId();
	}

	@SuppressWarnings("unchecked")
	private ArgumentCaptor<List<String>> listCaptor() {
		return (ArgumentCaptor<List<String>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(List.class);
	}
}
