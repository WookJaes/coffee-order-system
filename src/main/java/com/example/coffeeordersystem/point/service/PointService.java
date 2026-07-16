package com.example.coffeeordersystem.point.service;

import com.example.coffeeordersystem.global.exception.BusinessException;
import com.example.coffeeordersystem.global.exception.ErrorCode;
import com.example.coffeeordersystem.point.dto.PointChargeRequest;
import com.example.coffeeordersystem.point.dto.PointChargeResponse;
import com.example.coffeeordersystem.point.entity.Point;
import com.example.coffeeordersystem.point.entity.PointHistory;
import com.example.coffeeordersystem.point.entity.PointHistoryType;
import com.example.coffeeordersystem.point.repository.PointHistoryRepository;
import com.example.coffeeordersystem.point.repository.PointRepository;
import com.example.coffeeordersystem.user.entity.User;
import com.example.coffeeordersystem.user.repository.UserRepository;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PointService {

	private static final int MIN_CHARGE_AMOUNT = 1;
	private static final int MAX_CHARGE_AMOUNT = 100_000;

	private final UserRepository userRepository;
	private final PointRepository pointRepository;
	private final PointHistoryRepository pointHistoryRepository;

	@Transactional
	public PointChargeResponse charge(PointChargeRequest request) {
		validateChargeAmount(request.amount());
		User user = findUserById(request.userId());
		Point point = getOrCreatePoint(user);
		point.charge(request.amount());

		pointHistoryRepository.save(new PointHistory(user, PointHistoryType.CHARGE, request.amount(), point.getBalance()));

		return PointChargeResponse.of(point, request.amount());
	}

	private void validateChargeAmount(Integer amount) {
		if (amount == null || amount < MIN_CHARGE_AMOUNT || amount > MAX_CHARGE_AMOUNT) {
			throw new BusinessException(ErrorCode.INVALID_CHARGE_AMOUNT);
		}
	}

	private User findUserById(Long userId) {
		return userRepository.findByIdWithPessimisticLock(userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
	}

	private Point getOrCreatePoint(User user) {
		return pointRepository.findByUserIdWithPessimisticLock(user.getId())
			.orElseGet(() -> pointRepository.save(new Point(user, 0)));
	}
}
