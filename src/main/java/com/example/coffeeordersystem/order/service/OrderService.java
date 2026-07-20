package com.example.coffeeordersystem.order.service;

import com.example.coffeeordersystem.global.exception.BusinessException;
import com.example.coffeeordersystem.global.exception.ErrorCode;
import com.example.coffeeordersystem.menu.entity.Menu;
import com.example.coffeeordersystem.menu.entity.MenuStatus;
import com.example.coffeeordersystem.menu.repository.MenuRepository;
import com.example.coffeeordersystem.order.dto.OrderCreateRequest;
import com.example.coffeeordersystem.order.dto.OrderCreateResponse;
import com.example.coffeeordersystem.order.entity.Order;
import com.example.coffeeordersystem.order.entity.OrderEvent;
import com.example.coffeeordersystem.order.repository.OrderEventRepository;
import com.example.coffeeordersystem.order.repository.OrderRepository;
import com.example.coffeeordersystem.point.entity.Point;
import com.example.coffeeordersystem.point.entity.PointHistory;
import com.example.coffeeordersystem.point.entity.PointHistoryType;
import com.example.coffeeordersystem.point.repository.PointHistoryRepository;
import com.example.coffeeordersystem.point.repository.PointRepository;
import com.example.coffeeordersystem.user.entity.User;
import com.example.coffeeordersystem.user.repository.UserRepository;

import java.util.Optional;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OrderService {

	private final UserRepository userRepository;
	private final MenuRepository menuRepository;
	private final PointRepository pointRepository;
	private final PointHistoryRepository pointHistoryRepository;
	private final OrderRepository orderRepository;
	private final OrderEventRepository orderEventRepository;

	@Transactional
	public OrderCreateResponse create(OrderCreateRequest request, String idempotencyKey) {
		validateIdempotencyKey(idempotencyKey);

		Optional<OrderCreateResponse> existingOrderResponse = findExistingOrderResponse(request, idempotencyKey);
		if (existingOrderResponse.isPresent()) {
			return existingOrderResponse.get();
		}

		User user = findUserForUpdate(request.userId());
		Menu menu = findMenu(request.menuId());
		validateMenuStatus(menu);
		validateQuantity(request.quantity());
		int paymentAmount = calculatePaymentAmount(menu.getPrice(), request.quantity());
		Point point = findPoint(user);

		existingOrderResponse = findExistingOrderResponseWithPessimisticLock(request, idempotencyKey);
		if (existingOrderResponse.isPresent()) {
			return existingOrderResponse.get();
		}

		point.use(paymentAmount);
		Order order = orderRepository.save(new Order(user, menu, idempotencyKey, request.quantity(), paymentAmount));
		pointHistoryRepository.save(new PointHistory(
			user,
			order,
			PointHistoryType.USE,
			paymentAmount,
			point.getBalance()
		));
		orderEventRepository.save(new OrderEvent(order));

		return OrderCreateResponse.from(order, point);
	}

	private Optional<OrderCreateResponse> findExistingOrderResponse(OrderCreateRequest request, String idempotencyKey) {
		return orderRepository.findByUserIdAndIdempotencyKey(request.userId(), idempotencyKey)
			.map(order -> getExistingOrderResponse(order, request.menuId(), request.quantity()));
	}

	private Optional<OrderCreateResponse> findExistingOrderResponseWithPessimisticLock(
		OrderCreateRequest request,
		String idempotencyKey
	) {
		return orderRepository.findByUserIdAndIdempotencyKeyWithPessimisticLock(request.userId(), idempotencyKey)
			.map(order -> getExistingOrderResponse(order, request.menuId(), request.quantity()));
	}

	private User findUserForUpdate(Long userId) {
		return userRepository.findByIdWithPessimisticLock(userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
	}

	private Menu findMenu(Long menuId) {
		return menuRepository.findById(menuId)
			.orElseThrow(() -> new BusinessException(ErrorCode.MENU_NOT_FOUND));
	}

	private Point findPoint(User user) {
		return pointRepository.findByUserIdWithPessimisticLock(user.getId())
			.orElseThrow(() -> new BusinessException(ErrorCode.POINT_NOT_FOUND));
	}

	private OrderCreateResponse getExistingOrderResponse(Order order, Long menuId, Integer quantity) {
		if (!isSameOrderRequest(order, menuId, quantity)) {
			throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
		}
		PointHistory pointHistory = pointHistoryRepository.findByOrderIdWithPessimisticLock(order.getId())
			.orElseThrow(() -> new BusinessException(ErrorCode.POINT_NOT_FOUND));
		return OrderCreateResponse.from(order, pointHistory.getBalanceAfter());
	}

	private boolean isSameOrderRequest(Order order, Long menuId, Integer quantity) {
		return order.getMenu().getId().equals(menuId) && order.getQuantity().equals(quantity);
	}

	private void validateIdempotencyKey(String idempotencyKey) {
		if (idempotencyKey == null || idempotencyKey.isBlank()) {
			throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED);
		}
	}

	private void validateMenuStatus(Menu menu) {
		if (menu.getStatus() != MenuStatus.ACTIVE) {
			throw new BusinessException(ErrorCode.MENU_NOT_ON_SALE);
		}
	}

	private void validateQuantity(Integer quantity) {
		if (quantity == null || quantity < 1) {
			throw new BusinessException(ErrorCode.INVALID_ORDER_QUANTITY);
		}
	}

	private int calculatePaymentAmount(Integer menuPrice, Integer quantity) {
		long paymentAmount = (long)menuPrice * quantity;
		if (paymentAmount > Integer.MAX_VALUE) {
			throw new BusinessException(ErrorCode.INVALID_ORDER_QUANTITY);
		}
		return (int)paymentAmount;
	}

}
