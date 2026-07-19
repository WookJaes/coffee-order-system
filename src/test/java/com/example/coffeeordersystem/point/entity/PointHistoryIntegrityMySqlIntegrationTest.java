package com.example.coffeeordersystem.point.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class PointHistoryIntegrityMySqlIntegrationTest {

	@Container
	private static final MySQLContainer<?> mysql = new MySQLContainer<>(DockerImageName.parse("mysql:8.4.5"))
		.withEnv("MYSQL_ROOT_HOST", "%");

	@BeforeEach
	void migrateDatabase() throws SQLException {
		Flyway.configure()
			.dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
			.cleanDisabled(false)
			.load()
			.clean();
		Flyway.configure()
			.dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
			.locations("classpath:db/migration")
			.load()
			.migrate();

		insert("insert into users (id, name) values (1, '포인트 이력 사용자')");
		insert("insert into orders (id, user_id, menu_id, quantity, idempotency_key, order_price, status, ordered_at) "
			+ "values (1, 1, 1, 1, 'point-history-order-1', 4500, 'PAID', current_timestamp)");
		insert("insert into orders (id, user_id, menu_id, quantity, idempotency_key, order_price, status, ordered_at) "
			+ "values (2, 1, 1, 1, 'point-history-order-2', 4500, 'PAID', current_timestamp)");
	}

	@Test
	void CHARGE는_order_id없이_여러_건을_저장할_수_있다() {
		// given
		Long orderId = null;

		// when
		Throwable throwable = catchThrowable(() -> {
			insertPointHistory(orderId, "CHARGE", 10_000, 10_000);
			insertPointHistory(orderId, "CHARGE", 5_000, 15_000);
		});

		// then
		assertThat(throwable).isNull();
	}

	@Test
	void USE는_서로_다른_주문에_각각_저장할_수_있다() {
		// given
		Long firstOrderId = 1L;
		Long secondOrderId = 2L;

		// when
		Throwable throwable = catchThrowable(() -> {
			insertPointHistory(firstOrderId, "USE", 4_500, 5_500);
			insertPointHistory(secondOrderId, "USE", 4_500, 1_000);
		});

		// then
		assertThat(throwable).isNull();
	}

	@Test
	void CHARGE에_order_id가_있으면_제약_위반이다() {
		// given
		Long orderId = 1L;

		// when
		Throwable throwable = catchThrowable(() -> insertPointHistory(orderId, "CHARGE", 10_000, 10_000));

		// then
		assertThat(throwable)
			.isInstanceOf(SQLException.class);
	}

	@Test
	void USE에_order_id가_없으면_제약_위반이다() {
		// given
		Long orderId = null;

		// when
		Throwable throwable = catchThrowable(() -> insertPointHistory(orderId, "USE", 4_500, 5_500));

		// then
		assertThat(throwable)
			.isInstanceOf(SQLException.class);
	}

	@Test
	void 같은_주문에_USE를_두_번_저장하면_제약_위반이다() throws SQLException {
		// given
		Long orderId = 1L;
		insertPointHistory(orderId, "USE", 4_500, 5_500);

		// when
		Throwable throwable = catchThrowable(() -> insertPointHistory(orderId, "USE", 4_500, 1_000));

		// then
		assertThat(throwable)
			.isInstanceOf(SQLException.class);
	}

	private void insertPointHistory(Long orderId, String type, int amount, int balanceAfter) throws SQLException {
		try (Connection connection = mysql.createConnection("");
			 PreparedStatement statement = connection.prepareStatement(
				 "insert into point_histories (user_id, order_id, type, amount, balance_after) values (1, ?, ?, ?, ?)"
			 )) {
			if (orderId == null) {
				statement.setNull(1, java.sql.Types.BIGINT);
			} else {
				statement.setLong(1, orderId);
			}
			statement.setString(2, type);
			statement.setInt(3, amount);
			statement.setInt(4, balanceAfter);
			statement.executeUpdate();
		}
	}

	private void insert(String sql) throws SQLException {
		try (Connection connection = mysql.createConnection("");
			 PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.executeUpdate();
		}
	}
}
