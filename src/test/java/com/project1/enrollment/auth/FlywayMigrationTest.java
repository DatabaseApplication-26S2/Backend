package com.project1.enrollment.auth;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import java.sql.DriverManager;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class FlywayMigrationTest {
    @Test void migrationIsRepeatableAndPreservesExistingData() throws Exception {
        String url = "jdbc:h2:mem:migration-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        Flyway first = Flyway.configure().dataSource(url, "sa", "").load();
        assertEquals(1, first.migrate().migrationsExecuted);
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO users VALUES ('existing', 'hash', 'Existing user', 'STUDENT')");
        }
        Flyway restarted = Flyway.configure().dataSource(url, "sa", "").load();
        assertEquals(0, restarted.migrate().migrationsExecuted);
        restarted.validate();
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT COUNT(*) FROM users WHERE user_id = 'existing'")) {
            assertTrue(result.next());
            assertEquals(1, result.getInt(1));
        }
    }
}
