package com.crs.backend.auth;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class DemoSeedTest {
    @Test void seedAndResetPreserveSchemaAndEnrollmentCountInvariant() throws Exception {
        try (var postgres = new PostgreSQLContainer("postgres:17")) {
            postgres.start();
            var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            var jdbc = new JdbcTemplate(source);
            var flyway = Flyway.configure().dataSource(source).locations("classpath:db/migration", "classpath:db/seed").load();
            assertEquals(2, flyway.migrate().migrationsExecuted);
            assertEquals(0, flyway.migrate().migrationsExecuted);
            assertEquals(60, jdbc.queryForObject("SELECT count(*) FROM users WHERE role_cd='STUDENT'", Integer.class));
            assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM users WHERE role_cd='PROFESSOR'", Integer.class));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM users WHERE role_cd='ADMIN'", Integer.class));
            assertEquals(25, jdbc.queryForObject("SELECT count(*) FROM courses", Integer.class));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM enrollments WHERE enroll_type='ADMIN'", Integer.class));
            for (String id : java.util.List.of("20260001", "P1001", "A1001")) {
                String hash = jdbc.queryForObject("SELECT password_hash FROM users WHERE user_id=?", String.class, id);
                assertTrue(new BCryptPasswordEncoder().matches("Demo-password1!", hash));
            }
            jdbc.execute(Files.readString(Path.of("scripts/reset-demo.sql")));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM enrollments", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM courses WHERE active_cnt<>0", Integer.class));
            assertEquals(2, jdbc.queryForObject("SELECT concurrency_limit FROM service_settings", Integer.class));
            assertEquals(64, jdbc.queryForObject("SELECT count(*) FROM users", Integer.class));
            flyway.validate();
        }
    }
}
