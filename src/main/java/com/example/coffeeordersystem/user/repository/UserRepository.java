package com.example.coffeeordersystem.user.repository;

import com.example.coffeeordersystem.user.entity.User;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {
}
