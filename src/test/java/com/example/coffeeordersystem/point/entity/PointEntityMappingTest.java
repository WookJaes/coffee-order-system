package com.example.coffeeordersystem.point.entity;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.FetchType;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;

import org.junit.jupiter.api.Test;

class PointEntityMappingTest {

	@Test
	void 포인트는_사용자와_일대일_관계다() throws NoSuchFieldException {
		assertThat(Point.class.getDeclaredField("user").getAnnotation(OneToOne.class)).isNotNull();
	}

	@Test
	void 포인트와_포인트_이력의_사용자_연관관계는_지연_로딩한다() throws NoSuchFieldException {
		assertThat(Point.class.getDeclaredField("user").getAnnotation(OneToOne.class).fetch())
			.isEqualTo(FetchType.LAZY);
		assertThat(PointHistory.class.getDeclaredField("user").getAnnotation(ManyToOne.class).fetch())
			.isEqualTo(FetchType.LAZY);
	}
}
