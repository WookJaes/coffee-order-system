package com.example.coffeeordersystem.order.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.coffeeordersystem.menu.entity.Menu;
import com.example.coffeeordersystem.menu.entity.MenuStatus;
import com.example.coffeeordersystem.menu.repository.MenuRepository;
import com.example.coffeeordersystem.order.entity.Order;
import com.example.coffeeordersystem.ranking.dto.DailyMenuOrderCount;
import com.example.coffeeordersystem.user.entity.User;
import com.example.coffeeordersystem.user.repository.UserRepository;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class OrderRepositoryTest {

	@Autowired
	private OrderRepository orderRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private MenuRepository menuRepository;

	@Test
	void 최근_7일_PAID_주문을_날짜와_메뉴별로_집계한다() {
		// given
		User user = userRepository.save(new User("랭킹 사용자"));
		Menu americano = menuRepository.save(new Menu("아메리카노", 4_500, MenuStatus.ACTIVE));
		Menu latte = menuRepository.save(new Menu("카페라떼", 5_000, MenuStatus.ACTIVE));
		orderRepository.save(createOrder(user, americano, "ranking-1", LocalDateTime.of(2026, 7, 15, 9, 0)));
		orderRepository.save(createOrder(user, americano, "ranking-2", LocalDateTime.of(2026, 7, 15, 10, 0)));
		orderRepository.save(createOrder(user, latte, "ranking-3", LocalDateTime.of(2026, 7, 14, 12, 0)));
		orderRepository.save(createOrder(user, latte, "ranking-old", LocalDateTime.of(2026, 7, 8, 23, 59)));
		orderRepository.flush();

		// when
		List<DailyMenuOrderCount> counts = orderRepository.findDailyPaidMenuOrderCounts(
			LocalDateTime.of(2026, 7, 9, 0, 0),
			LocalDateTime.of(2026, 7, 16, 0, 0)
		);

		// then
		assertThat(counts).hasSize(2);
		assertThat(counts)
			.extracting(DailyMenuOrderCount::menuId, DailyMenuOrderCount::orderCount)
			.containsExactlyInAnyOrder(
				org.assertj.core.groups.Tuple.tuple(americano.getId(), 2L),
				org.assertj.core.groups.Tuple.tuple(latte.getId(), 1L)
			);
		assertThat(counts)
			.extracting(DailyMenuOrderCount::orderedDate)
			.allSatisfy(orderedDate -> assertThat(toLocalDate(orderedDate))
				.isIn(LocalDate.of(2026, 7, 14), LocalDate.of(2026, 7, 15)));
	}

	private Order createOrder(User user, Menu menu, String idempotencyKey, LocalDateTime orderedAt) {
		Order order = new Order(user, menu, idempotencyKey, 1, menu.getPrice());
		try {
			Field field = Order.class.getDeclaredField("orderedAt");
			field.setAccessible(true);
			field.set(order, orderedAt);
			return order;
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private LocalDate toLocalDate(Object orderedDate) {
		if (orderedDate instanceof LocalDate localDate) {
			return localDate;
		}
		return ((java.sql.Date) orderedDate).toLocalDate();
	}
}
