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
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "order_events")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderEvent extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@OneToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "order_id", nullable = false, unique = true)
	private Order order;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private User user;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "menu_id", nullable = false)
	private Menu menu;

	@Column(name = "payment_amount", nullable = false)
	private Integer paymentAmount;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private OrderEventStatus status;

	@Column(name = "retry_count", nullable = false)
	private Integer retryCount;

	public OrderEvent(Order order) {
		this.order = order;
		this.user = order.getUser();
		this.menu = order.getMenu();
		this.paymentAmount = order.getOrderPrice();
		this.status = OrderEventStatus.PENDING;
		this.retryCount = 0;
	}
}
