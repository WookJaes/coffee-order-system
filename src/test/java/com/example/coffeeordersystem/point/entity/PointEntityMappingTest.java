package com.example.coffeeordersystem.point.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.coffeeordersystem.global.exception.BusinessException;
import com.example.coffeeordersystem.global.exception.ErrorCode;
import com.example.coffeeordersystem.user.entity.User;
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

	@Test
	void 잔액이_최대값을_넘는_충전은_전용_오류로_거절하고_잔액을_보존한다() {
		// given
		Point point = new Point(new User("엔티티 오버플로 사용자"), Integer.MAX_VALUE - 99_999);

		// when
		assertThatThrownBy(() -> point.charge(100_000))
			.isInstanceOf(BusinessException.class)
			.extracting(exception -> ((BusinessException)exception).getErrorCode())
			.isEqualTo(ErrorCode.POINT_BALANCE_OVERFLOW);

		// then
		assertThat(point.getBalance()).isEqualTo(Integer.MAX_VALUE - 99_999);
	}
}
