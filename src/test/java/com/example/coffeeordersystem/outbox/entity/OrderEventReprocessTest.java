package com.example.coffeeordersystem.outbox.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.coffeeordersystem.menu.entity.Menu;
import com.example.coffeeordersystem.order.entity.Order;
import com.example.coffeeordersystem.order.entity.OrderEvent;
import com.example.coffeeordersystem.order.entity.OrderEventStatus;
import com.example.coffeeordersystem.user.entity.User;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class OrderEventReprocessTest {

	@Test
	void FAILED_이벤트_재처리는_PENDING으로_전환하고_재시도와_lease를_초기화하지만_오류는_보존한다() {
		OrderEvent event = event();
		LocalDateTime now = LocalDateTime.of(2026, 7, 19, 12, 0);
		ReflectionTestUtils.setField(event, "status", OrderEventStatus.FAILED);
		ReflectionTestUtils.setField(event, "retryCount", 4);
		ReflectionTestUtils.setField(event, "processingToken", "old-token");
		ReflectionTestUtils.setField(event, "processingStartedAt", now.minusMinutes(5));
		ReflectionTestUtils.setField(event, "nextAttemptAt", now.plusHours(1));
		ReflectionTestUtils.setField(event, "lastError", "broker unavailable");

		event.reprocess(now);

		assertThat(event.getStatus()).isEqualTo(OrderEventStatus.PENDING);
		assertThat(event.getRetryCount()).isZero();
		assertThat(event.getNextAttemptAt()).isEqualTo(now);
		assertThat(event.getProcessingToken()).isNull();
		assertThat(event.getProcessingStartedAt()).isNull();
		assertThat(event.getLastError()).isEqualTo("broker unavailable");
	}

	@Test
	void FAILED가_아닌_이벤트는_재처리할_수_없다() {
		OrderEvent event = event();

		assertThatThrownBy(() -> event.reprocess(LocalDateTime.now()))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("FAILED 상태의 Outbox 이벤트만 재처리할 수 있습니다.");
	}

	private OrderEvent event() {
		Order order = mock(Order.class);
		when(order.getUser()).thenReturn(mock(User.class));
		when(order.getMenu()).thenReturn(mock(Menu.class));
		when(order.getOrderPrice()).thenReturn(4_500);
		return new OrderEvent(order);
	}
}
