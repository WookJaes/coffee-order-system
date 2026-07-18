package com.example.coffeeordersystem.point.entity;

import com.example.coffeeordersystem.global.entity.BaseEntity;
import com.example.coffeeordersystem.global.exception.BusinessException;
import com.example.coffeeordersystem.global.exception.ErrorCode;
import com.example.coffeeordersystem.user.entity.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "points")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Point extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@OneToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false, unique = true)
	private User user;

	@Column(nullable = false)
	private Integer balance;

	public Point(User user, Integer balance) {
		this.user = user;
		this.balance = balance;
	}

	public void charge(Integer amount) {
		if (balance > Integer.MAX_VALUE - amount) {
			throw new BusinessException(ErrorCode.POINT_BALANCE_OVERFLOW);
		}
		balance += amount;
	}

	public void use(Integer amount) {
		if (balance < amount) {
			throw new BusinessException(ErrorCode.INSUFFICIENT_POINT);
		}
		balance -= amount;
	}
}
