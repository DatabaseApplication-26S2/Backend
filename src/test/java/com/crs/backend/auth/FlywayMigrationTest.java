package com.crs.backend.auth;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.sql.DriverManager;
import static org.junit.jupiter.api.Assertions.*;

class FlywayMigrationTest {
    @Test void migrationIsRepeatableAndPreservesExistingData() throws Exception {
        try (var postgres = new PostgreSQLContainer("postgres:17")) {
            postgres.start();
            String url = postgres.getJdbcUrl();
            String username = postgres.getUsername();
            String password = postgres.getPassword();
            Flyway first = Flyway.configure().dataSource(url, username, password)
                .locations("classpath:db/migration").load();
            assertEquals(1, first.migrate().migrationsExecuted);
            try (var connection = DriverManager.getConnection(url, username, password);
                 var statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO users VALUES ('existing', 'hash', 'Existing user', 'STUDENT')");
            }
            Flyway restarted = Flyway.configure().dataSource(url, username, password)
                .locations("classpath:db/migration").load();
            assertEquals(0, restarted.migrate().migrationsExecuted);
            restarted.validate();
            try (var connection = DriverManager.getConnection(url, username, password);
                 var statement = connection.createStatement();
                 var result = statement.executeQuery("SELECT COUNT(*) FROM users WHERE user_id = 'existing'")) {
                assertTrue(result.next());
                assertEquals(1, result.getInt(1));
            }
        }
    }
}
