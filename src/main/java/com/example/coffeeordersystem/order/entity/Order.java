package com.example.coffeeordersystem.order.entity;

import com.example.coffeeordersystem.global.entity.BaseEntity;
import com.example.coffeeordersystem.menu.entity.Menu;
import com.example.coffeeordersystem.user.entity.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(
	name = "orders",
	uniqueConstraints = @UniqueConstraint(
		name = "uk_orders_user_id_idempotency_key",
		columnNames = {"user_id", "idempotency_key"}
	)
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private User user;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "menu_id", nullable = false)
	private Menu menu;

	@Column(nullable = false)
	private Integer quantity;

	@Column(name = "idempotency_key", nullable = false)
	private String idempotencyKey;

	@Column(name = "order_price", nullable = false)
	private Integer orderPrice;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private OrderStatus status;

	@Column(name = "ordered_at", nullable = false)
	private LocalDateTime orderedAt;

	public Order(User user, Menu menu, String idempotencyKey, Integer quantity, Integer orderPrice) {
		this.user = user;
		this.menu = menu;
		this.idempotencyKey = idempotencyKey;
		this.quantity = quantity;
		this.orderPrice = orderPrice;
		this.status = OrderStatus.PAID;
		this.orderedAt = LocalDateTime.now();
	}
}
