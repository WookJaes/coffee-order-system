package com.example.coffeeordersystem.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.example.coffeeordersystem.global.exception.BusinessException;
import com.example.coffeeordersystem.global.exception.ErrorCode;
import com.example.coffeeordersystem.menu.entity.Menu;
import com.example.coffeeordersystem.menu.entity.MenuStatus;
import com.example.coffeeordersystem.menu.repository.MenuRepository;
import com.example.coffeeordersystem.order.dto.OrderCreateRequest;
import com.example.coffeeordersystem.order.entity.OrderEventStatus;
import com.example.coffeeordersystem.order.repository.OrderEventRepository;
import com.example.coffeeordersystem.order.repository.OrderRepository;
import com.example.coffeeordersystem.point.entity.Point;
import com.example.coffeeordersystem.point.entity.PointHistoryType;
import com.example.coffeeordersystem.point.repository.PointHistoryRepository;
import com.example.coffeeordersystem.point.repository.PointRepository;
import com.example.coffeeordersystem.point.service.PointService;
import com.example.coffeeordersystem.point.dto.PointChargeRequest;
import com.example.coffeeordersystem.user.entity.User;
import com.example.coffeeordersystem.user.repository.UserRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class OrderServiceTest {

	@Autowired
	private OrderService orderService;

	@Autowired
	private PointService pointService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private MenuRepository menuRepository;

	@Autowired
	private PointRepository pointRepository;

	@Autowired
	private PointHistoryRepository pointHistoryRepository;

	@Autowired
	private OrderRepository orderRepository;

	@Autowired
	private OrderEventRepository orderEventRepository;

	@Test
	void 주문_결제는_포인트를_차감하고_주문_사용이력_Outbox를_저장한다() {
		// given
		User user = userRepository.save(new User("주문 사용자"));
		Menu menu = menuRepository.save(new Menu("아메리카노", 4_500, MenuStatus.ACTIVE));
		pointRepository.save(new Point(user, 10_000));

		// when
		var response = orderService.create(new OrderCreateRequest(user.getId(), menu.getId(), 2), "order-key-1");

		// then
		assertThat(response.orderId()).isNotNull();
		assertThat(response.userId()).isEqualTo(user.getId());
		assertThat(response.menuId()).isEqualTo(menu.getId());
		assertThat(response.quantity()).isEqualTo(2);
		assertThat(response.paymentAmount()).isEqualTo(9_000);
		assertThat(response.remainingPoint()).isEqualTo(1_000);
		assertThat(response.status()).isEqualTo("PAID");
		assertThat(pointRepository.findByUserId(user.getId()).orElseThrow().getBalance()).isEqualTo(1_000);
		assertThat(orderRepository.findAll()).singleElement()
			.satisfies(order -> {
				assertThat(order.getQuantity()).isEqualTo(2);
				assertThat(order.getOrderPrice()).isEqualTo(9_000);
			});
		assertThat(pointHistoryRepository.findAll()).singleElement()
			.satisfies(history -> {
				assertThat(history.getOrder()).isNotNull();
				assertThat(history.getType()).isEqualTo(PointHistoryType.USE);
				assertThat(history.getAmount()).isEqualTo(9_000);
				assertThat(history.getBalanceAfter()).isEqualTo(1_000);
			});
		assertThat(orderEventRepository.findAll()).singleElement()
			.satisfies(event -> {
				assertThat(event.getStatus()).isEqualTo(OrderEventStatus.PENDING);
				assertThat(event.getPaymentAmount()).isEqualTo(9_000);
			});
	}

	@Test
	void SOLD_OUT_메뉴는_MENU_NOT_ON_SALE로_실패한다() {
		// given
		User user = userRepository.save(new User("품절 주문 사용자"));
		Menu menu = menuRepository.save(new Menu("품절 커피", 4_500, MenuStatus.SOLD_OUT));
		pointRepository.save(new Point(user, 10_000));

		// when
		BusinessException exception = createAndGetError(new OrderCreateRequest(user.getId(), menu.getId(), 1), "sold-out-key");

		// then
		assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.MENU_NOT_ON_SALE);
	}

	@Test
	void 존재하지_않는_사용자_메뉴_포인트는_각각_명확한_오류로_실패한다() {
		// given
		Menu activeMenu = menuRepository.save(new Menu("활성 커피", 4_500, MenuStatus.ACTIVE));
		User user = userRepository.save(new User("메뉴 없는 사용자"));

		// when
		BusinessException userException = createAndGetError(new OrderCreateRequest(999L, activeMenu.getId(), 1), "missing-user-key");
		BusinessException menuException = createAndGetError(new OrderCreateRequest(user.getId(), 999L, 1), "missing-menu-key");
		BusinessException pointException = createAndGetError(new OrderCreateRequest(user.getId(), activeMenu.getId(), 1), "missing-point-key");

		// then
		assertThat(userException.getErrorCode()).isEqualTo(ErrorCode.USER_NOT_FOUND);
		assertThat(menuException.getErrorCode()).isEqualTo(ErrorCode.MENU_NOT_FOUND);
		assertThat(pointException.getErrorCode()).isEqualTo(ErrorCode.POINT_NOT_FOUND);
	}

	@Test
	void 잔액이_부족하면_어떤_데이터도_변경하지_않는다() {
		// given
		User user = userRepository.save(new User("잔액 부족 사용자"));
		Menu menu = menuRepository.save(new Menu("비싼 커피", 4_500, MenuStatus.ACTIVE));
		pointRepository.save(new Point(user, 4_000));

		// when
		BusinessException exception = createAndGetError(new OrderCreateRequest(user.getId(), menu.getId(), 1), "insufficient-key");

		// then
		assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INSUFFICIENT_POINT);
		assertThat(pointRepository.findByUserId(user.getId()).orElseThrow().getBalance()).isEqualTo(4_000);
		assertThat(orderRepository.count()).isZero();
		assertThat(pointHistoryRepository.count()).isZero();
		assertThat(orderEventRepository.count()).isZero();
	}

	@Test
	void 같은_멱등성_키와_같은_메뉴는_기존_결과를_반환하고_한번만_차감한다() {
		// given
		User user = userRepository.save(new User("재시도 사용자"));
		Menu menu = menuRepository.save(new Menu("재시도 커피", 4_500, MenuStatus.ACTIVE));
		pointRepository.save(new Point(user, 10_000));
		OrderCreateRequest request = new OrderCreateRequest(user.getId(), menu.getId(), 2);

		// when
		var first = orderService.create(request, "same-key");
		var retry = orderService.create(request, "same-key");

		// then
		assertThat(retry).isEqualTo(first);
		assertThat(pointRepository.findByUserId(user.getId()).orElseThrow().getBalance()).isEqualTo(1_000);
		assertThat(orderRepository.count()).isEqualTo(1);
		assertThat(pointHistoryRepository.count()).isEqualTo(1);
		assertThat(orderEventRepository.count()).isEqualTo(1);
	}

	@Test
	void 멱등_재요청은_이후_포인트_변동과_무관하게_최초_주문_결과를_반환한다() {
		// given
		User user = userRepository.save(new User("멱등 결과 사용자"));
		Menu menu = menuRepository.save(new Menu("멱등 결과 커피", 4_500, MenuStatus.ACTIVE));
		Point point = pointRepository.save(new Point(user, 10_000));
		OrderCreateRequest request = new OrderCreateRequest(user.getId(), menu.getId(), 1);
		var first = orderService.create(request, "same-result-key");
		point.charge(1_000);

		// when
		var retry = orderService.create(request, "same-result-key");

		// then
		assertThat(retry).isEqualTo(first);
		assertThat(point.getBalance()).isEqualTo(6_500);
		assertThat(orderRepository.count()).isEqualTo(1);
		assertThat(pointHistoryRepository.count()).isEqualTo(1);
		assertThat(orderEventRepository.count()).isEqualTo(1);
	}

	@Test
	void 같은_멱등성_키로_다른_메뉴를_요청하면_충돌한다() {
		// given
		User user = userRepository.save(new User("키 충돌 사용자"));
		Menu firstMenu = menuRepository.save(new Menu("첫 커피", 4_500, MenuStatus.ACTIVE));
		Menu otherMenu = menuRepository.save(new Menu("다른 커피", 5_000, MenuStatus.ACTIVE));
		pointRepository.save(new Point(user, 20_000));

		// 동일 멱등성 키의 기존 주문을 생성한다.
		orderService.create(new OrderCreateRequest(user.getId(), firstMenu.getId(), 1), "conflict-key");

		// when
		BusinessException exception = createAndGetError(new OrderCreateRequest(user.getId(), otherMenu.getId(), 1), "conflict-key");

		// then
		assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
		assertThat(pointRepository.findByUserId(user.getId()).orElseThrow().getBalance()).isEqualTo(15_500);
		assertThat(orderRepository.count()).isEqualTo(1);
	}

	@Test
	void 같은_멱등성_키로_다른_수량을_요청하면_충돌한다() {
		// given
		User user = userRepository.save(new User("수량 키 충돌 사용자"));
		Menu menu = menuRepository.save(new Menu("수량 키 충돌 커피", 4_500, MenuStatus.ACTIVE));
		pointRepository.save(new Point(user, 20_000));

		// 동일 멱등성 키의 기존 주문을 생성한다.
		orderService.create(new OrderCreateRequest(user.getId(), menu.getId(), 1), "quantity-conflict-key");

		// when
		BusinessException exception = createAndGetError(new OrderCreateRequest(user.getId(), menu.getId(), 2), "quantity-conflict-key");

		// then
		assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
		assertThat(pointRepository.findByUserId(user.getId()).orElseThrow().getBalance()).isEqualTo(15_500);
		assertThat(orderRepository.count()).isEqualTo(1);
	}

	@Test
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	@DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
	void 동일_사용자의_동시_주문에서도_잔액은_음수가_되지_않고_성공_주문만_이력을_남긴다() throws Exception {
		// given
		User user = userRepository.saveAndFlush(new User("동시 주문 사용자"));
		Menu menu = menuRepository.saveAndFlush(new Menu("동시 주문 커피", 4_500, MenuStatus.ACTIVE));
		pointRepository.saveAndFlush(new Point(user, 10_000));

		// when
		List<Throwable> failures = runConcurrently(5, index ->
			orderService.create(new OrderCreateRequest(user.getId(), menu.getId(), 1), "concurrent-order-" + index)
		);

		// then
		assertThat(failures).hasSize(3)
			.allSatisfy(failure -> assertThat(((BusinessException)failure).getErrorCode()).isEqualTo(ErrorCode.INSUFFICIENT_POINT));
		assertThat(pointRepository.findByUserId(user.getId()).orElseThrow().getBalance()).isEqualTo(1_000);
		assertThat(orderRepository.count()).isEqualTo(2);
		assertThat(pointHistoryRepository.findAll()).hasSize(2)
			.allSatisfy(history -> assertThat(history.getType()).isEqualTo(PointHistoryType.USE));
		assertThat(orderEventRepository.count()).isEqualTo(2);
	}

	@Test
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	@DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
	void 동시_동일_멱등_요청은_주문_이력_Outbox를_한번만_생성한다() throws Exception {
		// given
		User user = userRepository.saveAndFlush(new User("동시 멱등 사용자"));
		Menu menu = menuRepository.saveAndFlush(new Menu("동시 멱등 커피", 4_500, MenuStatus.ACTIVE));
		pointRepository.saveAndFlush(new Point(user, 10_000));

		// when
		List<Throwable> failures = runConcurrently(5, ignored ->
			orderService.create(new OrderCreateRequest(user.getId(), menu.getId(), 1), "same-concurrent-key")
		);

		// then
		assertThat(failures).isEmpty();
		assertThat(pointRepository.findByUserId(user.getId()).orElseThrow().getBalance()).isEqualTo(5_500);
		assertThat(orderRepository.count()).isEqualTo(1);
		assertThat(pointHistoryRepository.count()).isEqualTo(1);
		assertThat(orderEventRepository.count()).isEqualTo(1);
	}

	@Test
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	@DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
	void 같은_사용자의_충전과_주문이_교차해도_잔액과_이력이_정합성을_유지한다() throws Exception {
		// given
		User user = userRepository.saveAndFlush(new User("교차 동시성 사용자"));
		Menu menu = menuRepository.saveAndFlush(new Menu("교차 동시성 커피", 4_500, MenuStatus.ACTIVE));
		pointRepository.saveAndFlush(new Point(user, 10_000));

		// when
		List<Throwable> failures = runConcurrently(10, index -> {
			if (index % 2 == 0) {
				pointService.charge(new PointChargeRequest(user.getId(), 1_000));
				return;
			}
			orderService.create(new OrderCreateRequest(user.getId(), menu.getId(), 1), "cross-order-" + index);
		});

		// then
		assertThat(failures)
			.allSatisfy(failure -> assertThat(((BusinessException)failure).getErrorCode())
				.isEqualTo(ErrorCode.INSUFFICIENT_POINT));
		int chargeTotal = pointHistoryRepository.findAll().stream()
			.filter(history -> history.getType() == PointHistoryType.CHARGE)
			.mapToInt(history -> history.getAmount())
			.sum();
		int useTotal = pointHistoryRepository.findAll().stream()
			.filter(history -> history.getType() == PointHistoryType.USE)
			.mapToInt(history -> history.getAmount())
			.sum();
		int balance = pointRepository.findByUserId(user.getId()).orElseThrow().getBalance();
		assertThat(balance).isEqualTo(10_000 + chargeTotal - useTotal).isGreaterThanOrEqualTo(0);
		assertThat(pointHistoryRepository.findAll().stream()
			.filter(history -> history.getType() == PointHistoryType.CHARGE))
			.hasSize(5);
		assertThat(pointHistoryRepository.findAll().stream()
			.filter(history -> history.getType() == PointHistoryType.USE))
			.hasSize((int)orderRepository.count());
		assertThat(orderEventRepository.count()).isEqualTo(orderRepository.count());
	}

	@Test
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	@DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
	void 교차_실행_중_잔액_부족_주문은_주문_사용이력_Outbox를_남기지_않는다() throws Exception {
		// given
		User user = userRepository.saveAndFlush(new User("교차 잔액 부족 사용자"));
		Menu menu = menuRepository.saveAndFlush(new Menu("교차 잔액 부족 커피", 4_500, MenuStatus.ACTIVE));
		pointRepository.saveAndFlush(new Point(user, 0));

		// when
		List<Throwable> failures = runConcurrently(2, index -> {
			if (index == 0) {
				pointService.charge(new PointChargeRequest(user.getId(), 1_000));
				return;
			}
			orderService.create(new OrderCreateRequest(user.getId(), menu.getId(), 1), "insufficient-cross-order");
		});

		// then
		assertThat(failures).singleElement()
			.satisfies(failure -> assertThat(((BusinessException)failure).getErrorCode())
				.isEqualTo(ErrorCode.INSUFFICIENT_POINT));
		assertThat(pointRepository.findByUserId(user.getId()).orElseThrow().getBalance()).isEqualTo(1_000);
		assertThat(pointHistoryRepository.findAll()).singleElement()
			.satisfies(history -> assertThat(history.getType()).isEqualTo(PointHistoryType.CHARGE));
		assertThat(orderRepository.count()).isZero();
		assertThat(orderEventRepository.count()).isZero();
	}

	@Test
	void 수량이_0이면_INVALID_ORDER_QUANTITY로_실패한다() {
		// given
		User user = userRepository.save(new User("수량 검증 사용자"));
		Menu menu = menuRepository.save(new Menu("수량 커피", 4_500, MenuStatus.ACTIVE));
		pointRepository.save(new Point(user, 10_000));

		// when
		BusinessException exception = createAndGetError(new OrderCreateRequest(user.getId(), menu.getId(), 0), "invalid-quantity-key");

		// then
		assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_ORDER_QUANTITY);
	}

	@Test
	void 멱등성_키가_없거나_공백이면_IDEMPOTENCY_KEY_REQUIRED로_실패한다() {
		// given
		OrderCreateRequest request = new OrderCreateRequest(1L, 1L, 1);

		// when
		BusinessException missingKeyException = createAndGetError(request, null);
		BusinessException blankKeyException = createAndGetError(request, " ");

		// then
		assertThat(missingKeyException.getErrorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REQUIRED);
		assertThat(blankKeyException.getErrorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REQUIRED);
	}

	private List<Throwable> runConcurrently(int requestCount, ConcurrentOrderRequest request) throws Exception {
		ExecutorService executorService = Executors.newFixedThreadPool(requestCount);
		CountDownLatch ready = new CountDownLatch(requestCount);
		CountDownLatch start = new CountDownLatch(1);
		try {
			List<Future<Throwable>> futures = new ArrayList<>();
			for (int index = 0; index < requestCount; index++) {
				int requestIndex = index;
				futures.add(executorService.submit(() -> {
					ready.countDown();
					start.await();
					try {
						request.execute(requestIndex);
						return null;
					} catch (Throwable throwable) {
						return throwable;
					}
				}));
			}
			ready.await();
			start.countDown();
			List<Throwable> failures = new ArrayList<>();
			for (Future<Throwable> future : futures) {
				Throwable failure = future.get();
				if (failure != null) {
					failures.add(failure);
				}
			}
			return failures;
		} finally {
			executorService.shutdownNow();
		}
	}

	@FunctionalInterface
	private interface ConcurrentOrderRequest {
		void execute(int index);
	}

	private BusinessException createAndGetError(OrderCreateRequest request, String key) {
		Throwable throwable = catchThrowable(() -> orderService.create(request, key));
		assertThat(throwable).isInstanceOf(BusinessException.class);
		return (BusinessException) throwable;
	}
}
		// then
		// then
		// then
