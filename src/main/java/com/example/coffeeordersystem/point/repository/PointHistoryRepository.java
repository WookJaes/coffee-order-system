package com.example.coffeeordersystem.point.repository;

import com.example.coffeeordersystem.point.entity.PointHistory;

import jakarta.persistence.LockModeType;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PointHistoryRepository extends JpaRepository<PointHistory, Long> {

	Optional<PointHistory> findByOrderId(Long orderId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select pointHistory from PointHistory pointHistory where pointHistory.order.id = :orderId")
	Optional<PointHistory> findByOrderIdWithPessimisticLock(@Param("orderId") Long orderId);
}
