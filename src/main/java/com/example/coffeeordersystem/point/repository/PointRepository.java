package com.example.coffeeordersystem.point.repository;

import com.example.coffeeordersystem.point.entity.Point;

import jakarta.persistence.LockModeType;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PointRepository extends JpaRepository<Point, Long> {

	Optional<Point> findByUserId(Long userId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select point from Point point where point.user.id = :userId")
	Optional<Point> findByUserIdForUpdate(@Param("userId") Long userId);
}
