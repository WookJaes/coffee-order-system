package com.example.coffeeordersystem.point.entity;

import com.example.coffeeordersystem.global.entity.BaseEntity;
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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "point_histories")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PointHistory extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private User user;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private PointHistoryType type;

	@Column(nullable = false)
	private Integer amount;

	@Column(name = "balance_after", nullable = false)
	private Integer balanceAfter;

	public PointHistory(User user, PointHistoryType type, Integer amount, Integer balanceAfter) {
		this.user = user;
		this.type = type;
		this.amount = amount;
		this.balanceAfter = balanceAfter;
	}
}
