package com.example.coffeeordersystem.global.config.flyway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.sql.ResultSet;
import java.sql.Statement;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class EnvironmentSeedFlywayIntegrationTest {

	@Container
	private static final MySQLContainer<?> mysql = new MySQLContainer<>(DockerImageName.parse("mysql:8.4.5"))
		.withEnv("MYSQL_ROOT_HOST", "%");

	@BeforeEach
	void cleanDatabase() {
		Flyway.configure()
			.dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
			.cleanDisabled(false)
			.load()
			.clean();
	}

	@Test
	void 신규_로컬_DB는_공통_스키마와_메뉴_seed_및_테스트_사용자를_적용한다() throws Exception {
		// given
		Flyway flyway = flyway("classpath:db/migration", "classpath:db/local-migration");

		// when
		flyway.migrate();

		// then
		assertThat(queryForInt("select count(*) from users where id = 1")).isEqualTo(1);
		assertThat(queryForInt("select count(*) from menus")).isEqualTo(5);
		assertThat(queryForInt("select count(*) from flyway_schema_history where version in ('1', '2', '3', '4', '5')"))
			.isEqualTo(5);
	}

	@Test
	void 신규_로컬이_아닌_DB는_공통_스키마와_메뉴_seed만_적용한다() throws Exception {
		// given
		Flyway flyway = flyway("classpath:db/migration");

		// when
		flyway.migrate();

		// then
		assertThat(queryForInt("select count(*) from users where id = 1")).isZero();
		assertThat(queryForInt("select count(*) from menus")).isEqualTo(5);
		assertThat(queryForInt("select count(*) from flyway_schema_history where version in ('1', '2', '4', '5')"))
			.isEqualTo(4);
		assertThat(queryForInt("select count(*) from flyway_schema_history where version = '3'")).isZero();
	}

	@Test
	void 기존_V3_이력이_있는_DB는_로컬이_아닌_설정으로_validate와_migrate에_성공한다() throws Exception {
		// given
		flyway("classpath:db/migration", "classpath:db/local-migration").migrate();

		Flyway nonLocalFlyway = flyway("classpath:db/migration");

		// when
		LegacyV3MigrationStrategy migrationStrategy = new LegacyV3MigrationStrategy();
		assertThatCode(() -> migrationStrategy.validate(nonLocalFlyway)).doesNotThrowAnyException();
		var migrationResult = migrationStrategy.migrate(nonLocalFlyway);

		// then
		assertThat(migrationResult.migrationsExecuted).isZero();
		assertThat(queryForInt("select count(*) from users where id = 1")).isEqualTo(1);
	}

	@Test
	void V3_외_누락된_versioned_migration이_있으면_이관을_허용하지_않는다() {
		// given
		flyway("classpath:db/migration", "classpath:db/local-migration", "classpath:db/legacy-migration").migrate();
		Flyway nonLocalFlyway = flyway("classpath:db/migration");

		// when
		Throwable throwable = catchThrowable(() -> new LegacyV3MigrationStrategy().migrate(nonLocalFlyway));

		// then
		assertThat(throwable)
			.isInstanceOf(RuntimeException.class)
			.hasMessageContaining("Detected applied migration not resolved locally");
	}

	private Flyway flyway(String... locations) {
		return Flyway.configure()
			.dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
			.locations(locations)
			.load();
	}

	private int queryForInt(String sql) throws Exception {
		try (var connection = mysql.createConnection(""); Statement statement = connection.createStatement();
			 ResultSet resultSet = statement.executeQuery(sql)) {
			resultSet.next();
			return resultSet.getInt(1);
		}
	}
}
