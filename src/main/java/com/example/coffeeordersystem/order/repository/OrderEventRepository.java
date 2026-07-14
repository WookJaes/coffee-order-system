package com.example.coffeeordersystem.order.repository;

import com.example.coffeeordersystem.order.entity.OrderEvent;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderEventRepository extends JpaRepository<OrderEvent, Long> {
}
