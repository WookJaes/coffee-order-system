package com.example.coffeeordersystem.order.repository;

import com.example.coffeeordersystem.order.entity.Order;
import com.example.coffeeordersystem.ranking.dto.DailyMenuOrderCount;

import jakarta.persistence.LockModeType;

import java.util.Optional;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, Long> {

	Optional<Order> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select orderEntity from Order orderEntity where orderEntity.user.id = :userId and orderEntity.idempotencyKey = :idempotencyKey")
	Optional<Order> findByUserIdAndIdempotencyKeyWithPessimisticLock(
		@Param("userId") Long userId,
		@Param("idempotencyKey") String idempotencyKey
	);

	@Query("""
		select new com.example.coffeeordersystem.ranking.dto.DailyMenuOrderCount(
			function('date', o.orderedAt), o.menu.id, count(o.id)
		)
		from Order o
		where o.status = com.example.coffeeordersystem.order.entity.OrderStatus.PAID
		  and o.orderedAt >= :start
		  and o.orderedAt < :end
		group by function('date', o.orderedAt), o.menu.id
		""")
	List<DailyMenuOrderCount> findDailyPaidMenuOrderCounts(
		@Param("start") LocalDateTime start,
		@Param("end") LocalDateTime end
	);
}
