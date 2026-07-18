package com.example.coffeeordersystem.global.config.flyway;

import java.util.Arrays;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.output.MigrateResult;

final class LegacyV3MigrationStrategy {

	MigrateResult migrate(Flyway flyway) {
		Flyway migrationFlyway = migrationFlyway(flyway);
		migrationFlyway.validate();
		return migrationFlyway.migrate();
	}

	void validate(Flyway flyway) {
		migrationFlyway(flyway).validate();
	}

	private Flyway migrationFlyway(Flyway flyway) {
		List<MigrationInfo> unresolvedAppliedMigrations = Arrays.stream(flyway.info().all())
			.filter(this::isUnresolvedAppliedMigration)
			.toList();

		if (!isOnlyLegacyV3(unresolvedAppliedMigrations)) {
			return flyway;
		}

		return Flyway.configure()
			.configuration(flyway.getConfiguration())
			.ignoreMigrationPatterns("versioned:missing")
			.load();
	}

	private boolean isUnresolvedAppliedMigration(MigrationInfo migrationInfo) {
		return migrationInfo.getState() == MigrationState.MISSING_SUCCESS
			|| migrationInfo.getState() == MigrationState.MISSING_FAILED
			|| migrationInfo.getState() == MigrationState.FUTURE_SUCCESS
			|| migrationInfo.getState() == MigrationState.FUTURE_FAILED;
	}

	private boolean isOnlyLegacyV3(List<MigrationInfo> unresolvedAppliedMigrations) {
		return unresolvedAppliedMigrations.size() == 1
			&& unresolvedAppliedMigrations.get(0).getState() == MigrationState.MISSING_SUCCESS
			&& unresolvedAppliedMigrations.get(0).getVersion().getVersion().equals("3");
	}

}
