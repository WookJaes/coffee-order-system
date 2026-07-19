package com.example.coffeeordersystem.outbox.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.coffeeordersystem.global.exception.BusinessException;
import com.example.coffeeordersystem.global.exception.ErrorCode;
import com.example.coffeeordersystem.menu.entity.Menu;
import com.example.coffeeordersystem.order.entity.Order;
import com.example.coffeeordersystem.order.entity.OrderEvent;
import com.example.coffeeordersystem.order.entity.OrderEventStatus;
import com.example.coffeeordersystem.order.repository.OrderEventRepository;
import com.example.coffeeordersystem.outbox.dto.OutboxStatusCountsResponse;
import com.example.coffeeordersystem.user.entity.User;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class OutboxAdminServiceTest {

	private final OrderEventRepository repository = mock(OrderEventRepository.class);
	private final OutboxAdminService service = new OutboxAdminService(repository);

	@Test
	void 존재하지_않는_이벤트는_404_예외를_반환한다() {
		given(repository.findByIdForUpdate(1L)).willReturn(Optional.empty());

		assertThatThrownBy(() -> service.reprocessFailedEvent(1L))
			.isInstanceOf(BusinessException.class)
			.extracting(exception -> ((BusinessException)exception).getErrorCode())
			.isEqualTo(ErrorCode.OUTBOX_EVENT_NOT_FOUND);
	}

	@Test
	void PENDING_PROCESSING_SENT_이벤트는_재처리를_거부한다() {
		for (OrderEventStatus status : new OrderEventStatus[] {OrderEventStatus.PENDING, OrderEventStatus.PROCESSING, OrderEventStatus.SENT}) {
			OrderEvent event = event();
			ReflectionTestUtils.setField(event, "status", status);
			given(repository.findByIdForUpdate((long)status.ordinal())).willReturn(Optional.of(event));

			assertThatThrownBy(() -> service.reprocessFailedEvent((long)status.ordinal()))
				.isInstanceOf(BusinessException.class)
				.extracting(exception -> ((BusinessException)exception).getErrorCode())
				.isEqualTo(ErrorCode.OUTBOX_EVENT_NOT_REPROCESSABLE);
		}
	}

	@Test
	void 상태별_건수를_반환한다() {
		given(repository.countByStatus(OrderEventStatus.PENDING)).willReturn(3L);
		given(repository.countByStatus(OrderEventStatus.PROCESSING)).willReturn(2L);
		given(repository.countByStatus(OrderEventStatus.FAILED)).willReturn(1L);

		assertThat(service.getStatusCounts()).isEqualTo(new OutboxStatusCountsResponse(3, 2, 1));
	}

	private OrderEvent event() {
		Order order = mock(Order.class);
		when(order.getUser()).thenReturn(mock(User.class));
		when(order.getMenu()).thenReturn(mock(Menu.class));
		when(order.getOrderPrice()).thenReturn(4_500);
		OrderEvent event = new OrderEvent(order);
		ReflectionTestUtils.setField(event, "status", OrderEventStatus.FAILED);
		ReflectionTestUtils.setField(event, "nextAttemptAt", LocalDateTime.now());
		return event;
	}
}
