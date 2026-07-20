package com.example.coffeeordersystem;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.example.coffeeordersystem.outbox.config.OutboxPublisherProperties;

@EnableJpaAuditing
@EnableScheduling
@SpringBootApplication
@EnableConfigurationProperties(OutboxPublisherProperties.class)
public class CoffeeordersystemApplication {

	public static void main(String[] args) {
		SpringApplication.run(CoffeeordersystemApplication.class, args);
	}

}
