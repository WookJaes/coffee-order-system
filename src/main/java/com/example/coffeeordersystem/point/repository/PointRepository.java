package com.example.coffeeordersystem.point.repository;

import com.example.coffeeordersystem.point.entity.Point;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PointRepository extends JpaRepository<Point, Long> {

	Optional<Point> findByUserId(Long userId);
}
