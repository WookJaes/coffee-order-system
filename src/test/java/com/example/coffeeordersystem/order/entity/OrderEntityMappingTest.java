package com.example.coffeeordersystem.order.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.coffeeordersystem.global.entity.BaseEntity;

import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.OneToOne;

import org.junit.jupiter.api.Test;

class OrderEntityMappingTest {

	@Test
	void 주문과_주문_이벤트는_BaseEntity를_상속하고_주문이벤트는_주문과_일대일_관계다() throws NoSuchFieldException {
		// given
		Enumerated orderStatus = Order.class.getDeclaredField("status").getAnnotation(Enumerated.class);
		Enumerated orderEventStatus = OrderEvent.class.getDeclaredField("status").getAnnotation(Enumerated.class);
		OneToOne orderEventOrder = OrderEvent.class.getDeclaredField("order").getAnnotation(OneToOne.class);

		// when
		Class<?> orderSuperclass = Order.class.getSuperclass();
		Class<?> orderEventSuperclass = OrderEvent.class.getSuperclass();

		// then
		assertThat(orderSuperclass).isEqualTo(BaseEntity.class);
		assertThat(orderEventSuperclass).isEqualTo(BaseEntity.class);
		assertThat(orderStatus.value()).isEqualTo(EnumType.STRING);
		assertThat(orderEventStatus.value())
			.isEqualTo(EnumType.STRING);
		assertThat(orderEventOrder).isNotNull();
	}

	@Test
	void 포인트_사용_이력_타입에_USE가_있다() {
		// given
		String typeName = "USE";

		// when
		var pointHistoryType = com.example.coffeeordersystem.point.entity.PointHistoryType.valueOf(typeName);

		// then
		assertThat(pointHistoryType)
			.isEqualTo(com.example.coffeeordersystem.point.entity.PointHistoryType.USE);
	}
}
