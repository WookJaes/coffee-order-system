package com.example.coffeeordersystem.point.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.coffeeordersystem.global.exception.BusinessException;
import com.example.coffeeordersystem.global.exception.ErrorCode;
import com.example.coffeeordersystem.point.dto.PointChargeRequest;
import com.example.coffeeordersystem.point.entity.Point;
import com.example.coffeeordersystem.point.entity.PointHistoryType;
import com.example.coffeeordersystem.point.repository.PointHistoryRepository;
import com.example.coffeeordersystem.point.repository.PointRepository;
import com.example.coffeeordersystem.user.entity.User;
import com.example.coffeeordersystem.user.repository.UserRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PointServiceTest {

	@Autowired
	private PointService pointService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private PointRepository pointRepository;

	@Autowired
	private PointHistoryRepository pointHistoryRepository;

	@Test
	void 포인트가_없는_사용자를_충전하면_잔액과_CHARGE_이력을_생성한다() {
		// given
		User user = userRepository.save(new User("포인트 사용자"));

		// when
		var response = pointService.charge(new PointChargeRequest(user.getId(), 10_000));

		// then
		assertThat(response.userId()).isEqualTo(user.getId());
		assertThat(response.chargedAmount()).isEqualTo(10_000);
		assertThat(response.balance()).isEqualTo(10_000);
		assertThat(pointRepository.findByUserId(user.getId())).get()
			.extracting(point -> point.getBalance())
			.isEqualTo(10_000);
		assertThat(pointHistoryRepository.findAll())
			.singleElement()
			.satisfies(history -> {
				assertThat(history.getAmount()).isEqualTo(10_000);
				assertThat(history.getBalanceAfter()).isEqualTo(10_000);
				assertThat(history.getType()).isEqualTo(PointHistoryType.CHARGE);
			});
	}

	@Test
	void 기존_포인트가_있으면_기존_잔액에_충전_금액을_더한다() {
		// given
		User user = userRepository.save(new User("기존 포인트 사용자"));
		pointService.charge(new PointChargeRequest(user.getId(), 3_000));

		// when
		var response = pointService.charge(new PointChargeRequest(user.getId(), 7_000));

		// then
		assertThat(response.balance()).isEqualTo(10_000);
		assertThat(pointRepository.findByUserId(user.getId())).get()
			.extracting(point -> point.getBalance())
			.isEqualTo(10_000);
		assertThat(pointHistoryRepository.findAll()).hasSize(2);
	}

	@Test
	void 최대_허용_잔액까지_충전하면_성공하고_CHARGE_이력을_남긴다() {
		// given
		User user = userRepository.save(new User("최대 잔액 사용자"));
		pointRepository.save(new Point(user, Integer.MAX_VALUE - 100_000));

		// when
		var response = pointService.charge(new PointChargeRequest(user.getId(), 100_000));

		// then
		assertThat(response.balance()).isEqualTo(Integer.MAX_VALUE);
		assertThat(pointRepository.findByUserId(user.getId()).orElseThrow().getBalance()).isEqualTo(Integer.MAX_VALUE);
		assertThat(pointHistoryRepository.findAll()).singleElement()
			.satisfies(history -> {
				assertThat(history.getType()).isEqualTo(PointHistoryType.CHARGE);
				assertThat(history.getAmount()).isEqualTo(100_000);
				assertThat(history.getBalanceAfter()).isEqualTo(Integer.MAX_VALUE);
			});
	}

	@Test
	void 최대_허용_잔액을_초과하면_전용_오류로_실패하고_잔액과_이력을_변경하지_않는다() {
		// given
		User user = userRepository.save(new User("오버플로 사용자"));
		int initialBalance = Integer.MAX_VALUE - 99_999;
		pointRepository.save(new Point(user, initialBalance));

		// when
		var exception = org.assertj.core.api.Assertions.catchThrowable(
			() -> pointService.charge(new PointChargeRequest(user.getId(), 100_000))
		);

		// then
		assertThat(exception).isInstanceOf(BusinessException.class);
		assertThat(((BusinessException)exception).getErrorCode()).isEqualTo(ErrorCode.POINT_BALANCE_OVERFLOW);
		assertThat(pointRepository.findByUserId(user.getId()).orElseThrow().getBalance()).isEqualTo(initialBalance);
		assertThat(pointHistoryRepository.findAll()).isEmpty();
	}

	@Test
	void 충전_금액이_0_이하면_INVALID_CHARGE_AMOUNT로_실패한다() {
		// given
		User user = userRepository.save(new User("금액 검증 사용자"));

		// when & then
		assertThatThrownBy(() -> pointService.charge(new PointChargeRequest(user.getId(), 0)))
			.isInstanceOf(BusinessException.class)
			.extracting(exception -> ((BusinessException)exception).getErrorCode())
			.isEqualTo(ErrorCode.INVALID_CHARGE_AMOUNT);
	}

	@Test
	void 충전_금액이_null이면_INVALID_CHARGE_AMOUNT로_실패한다() {
		// given
		User user = userRepository.save(new User("null 금액 사용자"));

		// when & then
		assertThatThrownBy(() -> pointService.charge(new PointChargeRequest(user.getId(), null)))
			.isInstanceOf(BusinessException.class)
			.extracting(exception -> ((BusinessException)exception).getErrorCode())
			.isEqualTo(ErrorCode.INVALID_CHARGE_AMOUNT);
	}

	@Test
	void 충전_금액이_최대_금액을_초과하면_INVALID_CHARGE_AMOUNT로_실패한다() {
		// given
		User user = userRepository.save(new User("최대 금액 사용자"));

		// when & then
		assertThatThrownBy(() -> pointService.charge(new PointChargeRequest(user.getId(), 100_001)))
			.isInstanceOf(BusinessException.class)
			.extracting(exception -> ((BusinessException)exception).getErrorCode())
			.isEqualTo(ErrorCode.INVALID_CHARGE_AMOUNT);
	}

	@Test
	void 존재하지_않는_사용자를_충전하면_USER_NOT_FOUND로_실패한다() {
		// when & then
		assertThatThrownBy(() -> pointService.charge(new PointChargeRequest(999L, 10_000)))
			.isInstanceOf(BusinessException.class)
			.extracting(exception -> ((BusinessException)exception).getErrorCode())
			.isEqualTo(ErrorCode.USER_NOT_FOUND);
	}

	@Test
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	@DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
	void 같은_사용자에게_동시_충전하면_모든_충전과_이력이_반영된다() throws Exception {
		// given
		User user = userRepository.saveAndFlush(new User("동시 충전 사용자"));
		int requestCount = 5;
		ExecutorService executorService = Executors.newFixedThreadPool(requestCount);
		CountDownLatch ready = new CountDownLatch(requestCount);
		CountDownLatch start = new CountDownLatch(1);

		try {
			Future<?>[] futures = new Future<?>[requestCount];
			for (int index = 0; index < requestCount; index++) {
				futures[index] = executorService.submit(() -> {
					ready.countDown();
					start.await();
					pointService.charge(new PointChargeRequest(user.getId(), 1_000));
					return null;
				});
			}

			ready.await();
			start.countDown();
			for (Future<?> future : futures) {
				future.get();
			}

			// then
			assertThat(pointRepository.findByUserId(user.getId())).get()
				.extracting(point -> point.getBalance())
				.isEqualTo(requestCount * 1_000);
			assertThat(pointHistoryRepository.findAll())
				.hasSize(requestCount)
				.allSatisfy(history -> assertThat(history.getType()).isEqualTo(PointHistoryType.CHARGE));
		} finally {
			executorService.shutdownNow();
		}
	}
}
