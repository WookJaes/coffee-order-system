package com.example.coffeeordersystem.point.repository;

import com.example.coffeeordersystem.point.entity.PointHistory;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PointHistoryRepository extends JpaRepository<PointHistory, Long> {

	Optional<PointHistory> findByOrderId(Long orderId);
}
