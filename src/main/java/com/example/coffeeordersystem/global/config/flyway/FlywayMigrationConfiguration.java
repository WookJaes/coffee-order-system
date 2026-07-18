package com.example.coffeeordersystem.global.config.flyway;

import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class FlywayMigrationConfiguration {

	@Bean
	FlywayMigrationStrategy flywayMigrationStrategy() {
		LegacyV3MigrationStrategy legacyV3MigrationStrategy = new LegacyV3MigrationStrategy();
		return legacyV3MigrationStrategy::migrate;
	}
}
