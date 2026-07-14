package com.example.coffeeordersystem.point.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.coffeeordersystem.point.entity.Point;
import com.example.coffeeordersystem.user.entity.User;

import java.lang.reflect.Field;

import org.junit.jupiter.api.Test;

class PointChargeResponseTest {

	@Test
	void 포인트와_충전_금액으로_충전_응답을_생성한다() throws ReflectiveOperationException {
		// given
		User user = new User("포인트 사용자");
		setId(user, 1L);
		Point point = new Point(user, 10_000);

		// when
		PointChargeResponse response = PointChargeResponse.of(point, 3_000);

		// then
		assertThat(response.userId()).isEqualTo(1L);
		assertThat(response.chargedAmount()).isEqualTo(3_000);
		assertThat(response.balance()).isEqualTo(10_000);
	}

	private void setId(User user, Long id) throws ReflectiveOperationException {
		Field field = User.class.getDeclaredField("id");
		field.setAccessible(true);
		field.set(user, id);
	}
}
