package com.example.coffeeordersystem;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@EnableJpaAuditing
@SpringBootApplication
public class CoffeeordersystemApplication {

	public static void main(String[] args) {
		SpringApplication.run(CoffeeordersystemApplication.class, args);
	}

}
