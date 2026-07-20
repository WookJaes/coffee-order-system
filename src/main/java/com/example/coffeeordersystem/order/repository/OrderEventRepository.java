package com.example.coffeeordersystem.order.repository;

import com.example.coffeeordersystem.order.entity.OrderEvent;
import com.example.coffeeordersystem.order.entity.OrderEventStatus;
import com.example.coffeeordersystem.outbox.dto.ClaimedOrderEventMessage;
import com.example.coffeeordersystem.ranking.dto.RebuildOrderEvent;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderEventRepository extends JpaRepository<OrderEvent, Long> {

	@Query("""
		select e.id
		from OrderEvent e
		where e.status = :status
		  and e.nextAttemptAt <= :now
		order by e.id
		""")
	List<Long> findCandidateIds(@Param("status") OrderEventStatus status, @Param("now") LocalDateTime now, Pageable pageable);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
		update OrderEvent e
		set e.status = 'PROCESSING',
			e.processingStartedAt = :now,
			e.processingToken = :token
		where e.id = :eventId
		  and e.status = 'PENDING'
		  and e.nextAttemptAt <= :now
		""")
	int claimIfPending(@Param("eventId") Long eventId, @Param("token") String token, @Param("now") LocalDateTime now);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
		update OrderEvent e
		set e.status = 'PROCESSING',
			e.processingStartedAt = :now,
			e.processingToken = :token
		where e.id in :candidateIds
		  and e.status = 'PENDING'
		  and e.nextAttemptAt <= :now
		""")
	int claimPendingBatch(
		@Param("candidateIds") List<Long> candidateIds,
		@Param("token") String token,
		@Param("now") LocalDateTime now
	);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
		update OrderEvent e
		set e.status = 'PENDING',
			e.processingStartedAt = null,
			e.processingToken = null,
			e.nextAttemptAt = :now
		where e.status = 'PROCESSING'
		  and e.processingStartedAt < :recoveryCutoff
		""")
	int recoverStaleProcessing(@Param("now") LocalDateTime now, @Param("recoveryCutoff") LocalDateTime recoveryCutoff);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
		update OrderEvent e
		set e.processingStartedAt = :now
		where e.status = 'PROCESSING'
		  and e.processingToken = :token
		""")
	int renewProcessingLeases(@Param("token") String token, @Param("now") LocalDateTime now);

	List<OrderEvent> findByProcessingTokenOrderById(String processingToken);

	@Query("""
		select new com.example.coffeeordersystem.outbox.dto.ClaimedOrderEventMessage(
			e.id,
			e.order.id,
			e.user.id,
			e.menu.id,
			e.paymentAmount,
			e.order.orderedAt
		)
		from OrderEvent e
		where e.processingToken = :processingToken
		order by e.id
		""")
	List<ClaimedOrderEventMessage> findClaimedMessagesByProcessingToken(
		@Param("processingToken") String processingToken
	);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select e from OrderEvent e where e.id = :eventId")
	Optional<OrderEvent> findByIdForUpdate(@Param("eventId") Long eventId);

	long countByStatus(OrderEventStatus status);

	@Query("""
		select new com.example.coffeeordersystem.ranking.dto.RebuildOrderEvent(
			e.id
		)
		from OrderEvent e
		where e.order.status = com.example.coffeeordersystem.order.entity.OrderStatus.PAID
		  and e.order.orderedAt >= :start
		  and e.order.orderedAt < :end
		""")
	List<RebuildOrderEvent> findPaidEventsForRankingRebuild(
		@Param("start") LocalDateTime start,
		@Param("end") LocalDateTime end
	);
}
