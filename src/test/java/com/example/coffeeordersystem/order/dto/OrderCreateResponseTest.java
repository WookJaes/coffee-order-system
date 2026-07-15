package com.example.coffeeordersystem.order.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.example.coffeeordersystem.menu.entity.Menu;
import com.example.coffeeordersystem.order.entity.Order;
import com.example.coffeeordersystem.order.entity.OrderStatus;
import com.example.coffeeordersystem.point.entity.Point;
import com.example.coffeeordersystem.user.entity.User;

import org.junit.jupiter.api.Test;

class OrderCreateResponseTest {

	@Test
	void 주문과_포인트에서_주문_결제_응답을_만든다() {
		// given
		Order order = mock(Order.class);
		User user = mock(User.class);
		Menu menu = mock(Menu.class);
		Point point = mock(Point.class);
		given(order.getId()).willReturn(1L);
		given(order.getUser()).willReturn(user);
		given(order.getMenu()).willReturn(menu);
		given(order.getOrderPrice()).willReturn(4_500);
		given(order.getQuantity()).willReturn(1);
		given(order.getStatus()).willReturn(OrderStatus.PAID);
		given(user.getId()).willReturn(1L);
		given(menu.getId()).willReturn(1L);
		given(point.getBalance()).willReturn(5_500);

		// when
		OrderCreateResponse response = OrderCreateResponse.from(order, point);

		// then
		assertThat(response).isEqualTo(new OrderCreateResponse(1L, 1L, 1L, 1, 4_500, 5_500, "PAID"));
	}
}
