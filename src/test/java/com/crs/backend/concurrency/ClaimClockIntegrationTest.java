package com.crs.backend.concurrency;

import com.crs.backend.TestFixtures;
import com.crs.backend.application.TicketDispatcher;
import com.crs.backend.application.TicketIntakeService;
import com.crs.backend.domain.entity.WaitingTicket;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(properties = {
    "queue.scheduler-fixed-delay-ms=3600000",
    "spring.datasource.hikari.maximum-pool-size=40"
})
@Import(ClaimClockIntegrationTest.Database.class)
class ClaimClockIntegrationTest {
    @Container
    static final GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("queue.waiting-key", () -> "concurrency-test:waiting");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Database {
        @Bean @ServiceConnection
        PostgreSQLContainer postgres() {
            return new PostgreSQLContainer(DockerImageName.parse("postgres:17"))
                .withCommand("postgres", "-c", "track_commit_timestamp=on");
        }
    }

    @Autowired JdbcTemplate sql;
    @Autowired JdbcClient jdbc;
    @Autowired DataSource dataSource;
    @Autowired TicketIntakeService intake;
    @Autowired TicketDispatcher dispatcher;
    private String course;
    private static final String CODE = "CONCUR01";
    private final List<String> sessions = new ArrayList<>();

    @BeforeEach void setup() {
        sql.execute("TRUNCATE enrollments, refresh_tokens, sessions, tickets, courses, users CASCADE");
        assertThat(sql.update("""
            INSERT INTO service_settings(singleton, concurrency_limit, last_changed_by, last_changed_at)
            VALUES (true, 100, NULL, NULL)
            ON CONFLICT (singleton) DO UPDATE SET concurrency_limit=100,
                last_changed_by=NULL, last_changed_at=NULL
            """)).isEqualTo(1);
        assertThat(sql.queryForObject("SELECT count(*) FROM service_settings WHERE singleton AND concurrency_limit=100 AND last_changed_by IS NULL AND last_changed_at IS NULL", Integer.class)).isEqualTo(1);
        sql.update("INSERT INTO users VALUES ('prof', 'hash', 'Professor', 'PROFESSOR')");
        course = UUID.randomUUID().toString();
        sql.update("""
            INSERT INTO courses(course_inst_id,course_code,professor_id,course_title,course_category,
              grading_type,max_capacity,min_enrollment,classroom,course_created_at)
            VALUES (CAST(? AS uuid),?,'prof','Course','MAJOR','ABSOLUTE',3,1,'Room',clock_timestamp())
            """, course, CODE);
        sessions.clear();
    }

    private void students(int count) {
        for (int i = 0; i < count; i++) {
            String user = "student" + i;
            sql.update("INSERT INTO users VALUES (?, 'hash', ?, 'STUDENT')", user, user);
            String session = TestFixtures.session(jdbc, user, "STUDENT");
            sql.update("UPDATE sessions SET entered_at=clock_timestamp() WHERE session_id=CAST(? AS uuid)", session);
            sessions.add(session);
        }
    }

    private long submit(int i) {
        return Long.parseLong(intake.intakeEnrollment("student" + i, sessions.get(i), course, CODE).ticketId());
    }

    private List<Long> concurrentSubmissions(int count) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(count);
        CountDownLatch ready = new CountDownLatch(count), start = new CountDownLatch(1);
        try {
            List<Future<Long>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                final int student = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    if (!start.await(30, TimeUnit.SECONDS)) throw new AssertionError("start gate timed out");
                    return submit(student);
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Long> ids = new ArrayList<>();
            for (Future<Long> future : futures) ids.add(future.get(90, TimeUnit.SECONDS));
            return ids;
        } finally {
            start.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test void t5_accountingLockAndLease() throws Exception {
        students(10);
        concurrentSubmissions(10);
        assertThat(sql.update("UPDATE service_settings SET concurrency_limit=2 WHERE singleton")).isEqualTo(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        assertThat(sql.queryForObject("SELECT count(*) FROM tickets WHERE target_course_inst_id=CAST(? AS uuid) AND status_cd='WAITING'", Integer.class, course)).isEqualTo(10);
        try (Connection settings = dataSource.getConnection(); Connection courseLock = dataSource.getConnection();
             Connection observer = dataSource.getConnection()) {
            settings.setAutoCommit(false);
            courseLock.setAutoCommit(false);
            try (var statement = settings.createStatement();
                 var result = statement.executeQuery("SELECT concurrency_limit FROM service_settings WHERE singleton FOR UPDATE")) {
                assertThat(result.next()).as("settings row exists and is locked").isTrue();
                assertThat(result.getInt(1)).isEqualTo(2);
                assertThat(result.next()).isFalse();
            }
            int blockerPid;
            String blockerXid;
            try (var statement = settings.createStatement();
                 var result = statement.executeQuery("SELECT pg_backend_pid(), pg_current_xact_id()::text")) {
                result.next();
                blockerPid = result.getInt(1);
                blockerXid = result.getString(2);
            }
            courseLock.createStatement().execute("SELECT 1 FROM courses WHERE course_inst_id='" + course + "' FOR UPDATE");
            observer.setAutoCommit(true);
            CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1), entering = new CountDownLatch(2);
            Callable<Integer> claim = () -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("start timeout");
                entering.countDown();
                return dispatcher.claimAndDispatch();
            };
            Future<Integer> first = pool.submit(claim), second = pool.submit(claim);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(entering.await(10, TimeUnit.SECONDS)).isTrue();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            boolean blocked = false;
            // pg_blocking_pids reports the actual lock graph, independent of SQL formatting.
            // Explicitly clear PostgreSQL's statistics snapshot on the observing connection.
            try (var clear = observer.createStatement();
                 var probe = observer.prepareStatement("""
                     SELECT EXISTS (
                         SELECT 1 FROM pg_stat_activity
                         WHERE datname=current_database()
                           AND ? = ANY(pg_blocking_pids(pid))
                     )
                     """)) {
                probe.setInt(1, blockerPid);
                while (System.nanoTime() < deadline) {
                    clear.execute("SELECT pg_stat_clear_snapshot()");
                    try (var result = probe.executeQuery()) {
                        result.next();
                        blocked = result.getBoolean(1);
                    }
                    if (blocked) break;
                    // Propagate dispatcher errors instead of reporting them as a lock-observation timeout.
                    if (first.isDone()) first.get(1, TimeUnit.SECONDS);
                    if (second.isDone()) second.get(1, TimeUnit.SECONDS);
                    Thread.sleep(10);
                }
            }
            assertThat(blocked).as("dispatcher waits on the settings lock holder").isTrue();
            assertThat(sql.queryForObject("SELECT count(*) FROM tickets WHERE status_cd='PROCESSING'", Integer.class)).isZero();
            settings.commit();
            Instant release = sql.queryForObject("SELECT pg_xact_commit_timestamp(CAST(? AS xid))", java.sql.Timestamp.class, blockerXid).toInstant();
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            int max = 0;
            while (System.nanoTime() < deadline) {
                int n = sql.queryForObject("SELECT count(*) FROM tickets WHERE status_cd='PROCESSING'", Integer.class);
                max = Math.max(max, n);
                assertThat(n).isLessThanOrEqualTo(2);
                if (n == 2 && (first.isDone() || second.isDone())) break;
                Thread.sleep(10);
            }
            assertThat(max).isEqualTo(2);
            List<Map<String,Object>> claimed = sql.queryForList("SELECT ticket_issued_at,processing_started_at,lease_info FROM tickets WHERE status_cd='PROCESSING' ORDER BY ticket_id");
            assertThat(claimed).hasSize(2);
            assertThat(claimed.get(0).get("processing_started_at"))
                .as("one start time for the claim batch").isEqualTo(claimed.get(1).get("processing_started_at"));
            assertThat(claimed.get(0).get("lease_info"))
                .as("one lease expiry for the claim batch").isEqualTo(claimed.get(1).get("lease_info"));
            for (Map<String,Object> row : claimed) {
                Instant issued = ((java.sql.Timestamp) row.get("ticket_issued_at")).toInstant();
                Instant started = ((java.sql.Timestamp) row.get("processing_started_at")).toInstant();
                Instant lease = ((java.sql.Timestamp) row.get("lease_info")).toInstant();
                assertThat(started).isAfter(release).isAfter(issued);
                assertThat(started).as("claim is not earlier than intake").isAfterOrEqualTo(issued);
                assertThat(java.time.Duration.between(started, lease)).isEqualTo(WaitingTicket.LEASE);
                assertThat(java.time.Duration.between(started, lease)).isEqualTo(java.time.Duration.ofSeconds(30));
            }
            courseLock.commit();
            assertThat(first.get(30, TimeUnit.SECONDS) + second.get(30, TimeUnit.SECONDS)).isEqualTo(2);
            assertThat(dispatcher.awaitIdle(java.time.Duration.ofSeconds(30))).as("workers finish after the course lock is released").isTrue();
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }
}
