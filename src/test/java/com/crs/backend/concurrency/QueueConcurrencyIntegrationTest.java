package com.crs.backend.concurrency;

import com.crs.backend.TestFixtures;
import com.crs.backend.application.TicketDispatcher;
import com.crs.backend.application.TicketIntakeService;
import com.crs.backend.application.handler.EnrollmentTicketHandler;
import com.crs.backend.auth.TokenCodec;
import com.crs.backend.domain.entity.WaitingTicket;
import com.crs.backend.domain.enums.UserRole;
import com.crs.backend.adapter.out.persistence.jpa.WaitingTicketJpaRepository;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(properties = {
    "queue.scheduler-fixed-delay-ms=3600000",
    "spring.datasource.hikari.maximum-pool-size=40"
})
@Import(QueueConcurrencyIntegrationTest.Database.class)
@AutoConfigureMockMvc
class QueueConcurrencyIntegrationTest {
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
    @Autowired EnrollmentTicketHandler enrollmentHandler;
    @Autowired WaitingTicketJpaRepository tickets;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TokenCodec tokens;
    private String course;
    private static final String CODE = "CONCUR01";
    private final List<String> sessions = new ArrayList<>();

    @BeforeEach void setup() {
        // This class owns its PostgreSQL container; no other test context's data is touched.
        sql.execute("TRUNCATE enrollments, refresh_tokens, sessions, tickets, courses, users CASCADE");
        // TRUNCATE users CASCADE also truncates service_settings through last_changed_by.
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
            pool.awaitTermination(30, TimeUnit.SECONDS);
        }
    }

    private void dispatchUntilFinished(int expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
        while (System.nanoTime() < deadline) {
            Integer finished = sql.queryForObject("SELECT count(*) FROM tickets WHERE target_course_inst_id=CAST(? AS uuid) AND status_cd IN ('SUCCEEDED','REJECTED')", Integer.class, course);
            if (finished != null && finished == expected) return;
            dispatcher.claimAndDispatch();
            assertThat(dispatcher.awaitIdle(Duration.ofSeconds(60))).isTrue();
        }
        throw new AssertionError("tickets did not finish within 90 seconds");
    }

    @Test void t1_capacityAndSameCourseFifo() throws Exception {
        students(30);
        List<Long> ids = concurrentSubmissions(30);
        List<Long> ordered = ids.stream().sorted().toList();
        // Set up a valid claimed batch after real intake. The real claim path is covered by T4 here and by ClaimClockIntegrationTest (T5).
        assertThat(sql.update("""
            UPDATE tickets SET status_cd='PROCESSING', generation_no=generation_no+1,
                slot_claim_ref=generation_no+1, owner_worker_ref='test-worker',
                processing_started_at=clock_timestamp(),
                lease_info=clock_timestamp()+interval '30 seconds'
            WHERE target_course_inst_id=CAST(? AS uuid) AND status_cd='WAITING'
            """, course)).isEqualTo(30);
        List<WaitingTicket> snapshots = ordered.stream().map(id -> tickets.findById(id).orElseThrow()).toList();
        ExecutorService workers = Executors.newFixedThreadPool(30);
        CountDownLatch startOthers = new CountDownLatch(1), othersReady = new CountDownLatch(29);
        try (Connection lock = dataSource.getConnection()) {
            lock.setAutoCommit(false);
            int blocker;
            try (var statement = lock.createStatement();
                 var result = statement.executeQuery("SELECT pg_backend_pid()")) {
                result.next();
                blocker = result.getInt(1);
            }
            lock.createStatement().execute("SELECT 1 FROM courses WHERE course_inst_id='" + course + "' FOR UPDATE");
            WaitingTicket later = snapshots.get(29);
            Map<String,Object> laterBefore = sql.queryForMap(
                "SELECT generation_no, retry_count FROM tickets WHERE ticket_id=?", later.getTicketId());
            Future<?> firstArrival = workers.submit(() -> enrollmentHandler.handle(later, later.getGenerationNo()));
            List<Future<?>> remaining = new ArrayList<>();
            for (int i = 0; i < 29; i++) {
                WaitingTicket ticket = snapshots.get(i);
                remaining.add(workers.submit(() -> {
                    othersReady.countDown();
                    if (!startOthers.await(30, TimeUnit.SECONDS)) throw new AssertionError("worker start timeout");
                    enrollmentHandler.handle(ticket, ticket.getGenerationNo());
                    return null;
                }));
            }
            assertThat(othersReady.await(30, TimeUnit.SECONDS)).isTrue();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            boolean waiting = false;
            while (System.nanoTime() < deadline) {
                sql.execute("SELECT pg_stat_clear_snapshot()");
                waiting = Boolean.TRUE.equals(sql.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM pg_stat_activity
                      WHERE datname=current_database() AND ? = ANY(pg_blocking_pids(pid)))
                    """, Boolean.class, blocker));
                if (waiting || firstArrival.isDone()) break;
                Thread.sleep(10);
            }
            assertThat(waiting).as("later worker must not wait for the course lock").isFalse();
            assertThat(firstArrival.isDone()).as("later worker finishes while the course lock is held").isTrue();
            firstArrival.get(1, TimeUnit.SECONDS);
            Map<String,Object> laterState = sql.queryForMap("""
                SELECT status_cd, result_cd, generation_no, retry_count, owner_worker_ref,
                       lease_info, slot_claim_ref, processing_started_at
                FROM tickets WHERE ticket_id=?
                """, later.getTicketId());
            assertThat(laterState.get("status_cd")).isEqualTo("WAITING");
            assertThat(laterState.get("result_cd")).as("later ticket is not decided").isNull();
            assertThat(laterState.get("generation_no")).isEqualTo(laterBefore.get("generation_no"));
            assertThat(laterState.get("retry_count")).isEqualTo(laterBefore.get("retry_count"));
            assertThat(laterState.get("owner_worker_ref")).isNull();
            assertThat(laterState.get("lease_info")).isNull();
            assertThat(laterState.get("slot_claim_ref")).isNull();
            assertThat(laterState.get("processing_started_at")).isNull();
            assertThat(sql.queryForObject("""
                SELECT count(*) FROM tickets WHERE target_course_inst_id=CAST(? AS uuid)
                  AND ticket_id < ? AND status_cd IN ('WAITING','PROCESSING')
                """, Integer.class, course, later.getTicketId())).isEqualTo(29);
            startOthers.countDown();
            lock.commit();
            firstArrival.get(30, TimeUnit.SECONDS);
            for (Future<?> future : remaining) future.get(30, TimeUnit.SECONDS);
            // Retry workers which correctly deferred behind earlier incomplete tickets.
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
            while (System.nanoTime() < deadline) {
                int finished = sql.queryForObject("SELECT count(*) FROM tickets WHERE target_course_inst_id=CAST(? AS uuid) AND status_cd IN ('SUCCEEDED','REJECTED')", Integer.class, course);
                if (finished == 30) break;
                List<Long> processing = sql.queryForList("SELECT ticket_id FROM tickets WHERE target_course_inst_id=CAST(? AS uuid) AND status_cd='PROCESSING' ORDER BY ticket_id", Long.class, course);
                for (long id : processing) {
                    WaitingTicket ticket = tickets.findById(id).orElseThrow();
                    enrollmentHandler.handle(ticket, ticket.getGenerationNo());
                }
                dispatcher.claimAndDispatch();
                assertThat(dispatcher.awaitIdle(Duration.ofSeconds(60))).isTrue();
            }
        } finally {
            startOthers.countDown();
            workers.shutdownNow();
            workers.awaitTermination(30, TimeUnit.SECONDS);
        }
        assertThat(sql.queryForObject("SELECT count(*) FROM tickets WHERE target_course_inst_id=CAST(? AS uuid) AND result_cd='OK'", Integer.class, course)).isEqualTo(3);
        assertThat(sql.queryForObject("SELECT count(*) FROM tickets WHERE target_course_inst_id=CAST(? AS uuid) AND result_cd='E07'", Integer.class, course)).isEqualTo(27);
        assertThat(sql.queryForObject("SELECT active_cnt FROM courses WHERE course_inst_id=CAST(? AS uuid)", Integer.class, course)).isEqualTo(3);
        assertThat(sql.queryForObject("SELECT count(*) FROM enrollments WHERE course_inst_id=CAST(? AS uuid) AND enroll_status='ENROLLED'", Integer.class, course)).isEqualTo(3);
        assertThat(sql.queryForObject("""
            SELECT count(*) - count(DISTINCT student_id) FROM enrollments
            WHERE course_inst_id=CAST(? AS uuid) AND enroll_status='ENROLLED'
            """, Long.class, course)).isZero();
        List<Long> winners = sql.queryForList("SELECT ticket_id FROM tickets WHERE target_course_inst_id=CAST(? AS uuid) AND result_cd='OK' ORDER BY ticket_id", Long.class, course);
        assertThat(winners).containsExactlyElementsOf(ids.stream().sorted().limit(3).toList());
    }

    @Test void t2_duplicateIncompleteAndResubmitAfterSuccess() throws Exception {
        students(1);
        String access = tokens.issue(sessions.get(0), "student0", UserRole.STUDENT, Instant.now().plusSeconds(3600), Instant.now()).value();
        String body = "{\"courseCode\":\"" + CODE + "\"}";
        var first = mvc.perform(post("/api/v1/me/enrollments").header("Authorization", "Bearer " + access)
                .contentType("application/json").content(body)).andExpect(status().isAccepted()).andReturn();
        String id = json.readTree(first.getResponse().getContentAsString()).get("ticketId").asText();
        var duplicate = mvc.perform(post("/api/v1/me/enrollments").header("Authorization", "Bearer " + access)
                .contentType("application/json").content(body)).andExpect(status().isConflict()).andReturn();
        var problem = json.readTree(duplicate.getResponse().getContentAsString());
        assertThat(problem.get("code").asText()).isEqualTo("E03");
        assertThat(problem.get("activeTicketId").asText()).isEqualTo(id);
        assertThat(sql.queryForObject("SELECT count(*) FROM tickets WHERE target_course_inst_id=CAST(? AS uuid)", Integer.class, course)).isEqualTo(1);
        dispatchUntilFinished(1);
        assertThat(sql.queryForObject("SELECT result_cd FROM tickets WHERE ticket_id=?", String.class, Long.parseLong(id))).isEqualTo("OK");
        var again = mvc.perform(post("/api/v1/me/enrollments").header("Authorization", "Bearer " + access)
                .contentType("application/json").content(body)).andExpect(status().isAccepted()).andReturn();
        long second = Long.parseLong(json.readTree(again.getResponse().getContentAsString()).get("ticketId").asText());
        dispatchUntilFinished(2);
        assertThat(sql.queryForObject("SELECT result_cd FROM tickets WHERE ticket_id=?", String.class, second)).isEqualTo("E06");
    }

    @Test void t4_commitAndClaimFollowTicketOrder() throws Exception {
        students(200);
        List<Long> ids = concurrentSubmissions(200);
        // Read xmin before dispatch updates the ticket rows. Equal timestamps cannot establish order.
        List<Map<String,Object>> commits = sql.queryForList("SELECT ticket_id, pg_xact_commit_timestamp(xmin) AS committed FROM tickets WHERE target_course_inst_id=CAST(? AS uuid) ORDER BY ticket_id", course);
        assertThat(commits).hasSize(200);
        Instant previous = null;
        for (Map<String,Object> row : commits) {
            Instant current = ((java.sql.Timestamp) row.get("committed")).toInstant();
            if (previous != null) assertThat(current).isAfterOrEqualTo(previous);
            previous = current;
        }
        assertThat(ids).doesNotHaveDuplicates();
        // Preserve every claim before a handler can return a ticket to WAITING.
        sql.execute("""
            CREATE TABLE t4_claim_observations (
                observation_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                ticket_id bigint NOT NULL, generation_no integer NOT NULL,
                processing_started_at timestamptz NOT NULL
            )
            """);
        try {
            sql.execute("""
                CREATE FUNCTION t4_record_claim() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF OLD.status_cd = 'WAITING' AND NEW.status_cd = 'PROCESSING' THEN
                        INSERT INTO t4_claim_observations(ticket_id, generation_no, processing_started_at)
                        VALUES (NEW.ticket_id, NEW.generation_no, NEW.processing_started_at);
                    END IF;
                    RETURN NEW;
                END;
                $$
                """);
            sql.execute("""
                CREATE TRIGGER t4_record_claim AFTER UPDATE ON tickets
                FOR EACH ROW EXECUTE FUNCTION t4_record_claim()
                """);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
            long lastObservation = 0;
            int finished = 0;
            while (System.nanoTime() < deadline) {
                finished = sql.queryForObject("SELECT count(*) FROM tickets WHERE target_course_inst_id=CAST(? AS uuid) AND status_cd IN ('SUCCEEDED','REJECTED')", Integer.class, course);
                if (finished == 200) break;
                List<Long> waiting = sql.queryForList("SELECT ticket_id FROM tickets WHERE status_cd='WAITING' ORDER BY ticket_id", Long.class);
                int claimedCount = dispatcher.claimAndDispatch();
                // Wait for the batch's handlers (including safe-wait returns to WAITING) so the next WAITING snapshot is stable.
                assertThat(dispatcher.awaitIdle(Duration.ofSeconds(60))).isTrue();
                List<Map<String,Object>> claims = sql.queryForList("""
                    SELECT observation_id, ticket_id, generation_no, processing_started_at
                    FROM t4_claim_observations WHERE observation_id > ? ORDER BY observation_id
                    """, lastObservation);
                assertThat(claims).as("every claimed ticket is recorded").hasSize(claimedCount);
                List<Long> claimedIds = claims.stream()
                    .map(row -> ((Number) row.get("ticket_id")).longValue()).sorted().toList();
                assertThat(claimedIds).as("each batch claims the smallest waiting tickets")
                    .containsExactlyElementsOf(waiting.subList(0, claimedCount));
                for (Map<String,Object> claim : claims) {
                    assertThat(((Number) claim.get("generation_no")).intValue()).isPositive();
                    assertThat(claim.get("processing_started_at")).isNotNull();
                    lastObservation = ((Number) claim.get("observation_id")).longValue();
                }
            }
            assertThat(finished).as("all 200 tickets finish within 90 seconds").isEqualTo(200);
        } finally {
            sql.execute("DROP TRIGGER IF EXISTS t4_record_claim ON tickets");
            sql.execute("DROP FUNCTION IF EXISTS t4_record_claim()");
            sql.execute("DROP TABLE t4_claim_observations");
        }
    }

    @Test void t6_staleGenerationCannotFinishOrMutateBusinessData() {
        students(1);
        long id = submit(0);
        // The old worker obtains its PROCESSING snapshot before reassignment.
        assertThat(sql.update("""
            UPDATE tickets SET status_cd='PROCESSING', generation_no=1, slot_claim_ref=1,
                owner_worker_ref='old-worker', processing_started_at=clock_timestamp(),
                lease_info=clock_timestamp()+interval '30 seconds'
            WHERE ticket_id=? AND status_cd='WAITING'
            """, id)).isEqualTo(1);
        WaitingTicket staleSnapshot = tickets.findById(id).orElseThrow();
        int staleGeneration = staleSnapshot.getGenerationNo();
        assertThat(staleGeneration).isEqualTo(1);
        // JdbcTemplate uses a separate committed transaction from the detached snapshot.
        assertThat(sql.update("""
            UPDATE tickets SET generation_no=generation_no+1, slot_claim_ref=generation_no+1,
                owner_worker_ref='new-worker', processing_started_at=clock_timestamp(),
                lease_info=clock_timestamp()+interval '30 seconds'
            WHERE ticket_id=? AND generation_no=? AND status_cd='PROCESSING'
            """, id, staleGeneration)).isEqualTo(1);
        Map<String,Object> before = sql.queryForMap("SELECT *, xmin::text AS row_version FROM tickets WHERE ticket_id=?", id);
        List<Map<String,Object>> enrollments = sql.queryForList("SELECT * FROM enrollments WHERE course_inst_id=CAST(? AS uuid)", course);
        int active = sql.queryForObject("SELECT active_cnt FROM courses WHERE course_inst_id=CAST(? AS uuid)", Integer.class, course);
        WaitingTicket currentSnapshot = tickets.findById(id).orElseThrow();
        assertThat(currentSnapshot.getGenerationNo()).isEqualTo(staleGeneration + 1);
        enrollmentHandler.handle(staleSnapshot, staleGeneration);
        // An unchanged xmin establishes that the stale handler updated zero ticket rows.
        assertThat(sql.queryForMap("SELECT *, xmin::text AS row_version FROM tickets WHERE ticket_id=?", id)).isEqualTo(before);
        assertThat(sql.queryForList("SELECT * FROM enrollments WHERE course_inst_id=CAST(? AS uuid)", course)).isEqualTo(enrollments);
        assertThat(sql.queryForObject("SELECT active_cnt FROM courses WHERE course_inst_id=CAST(? AS uuid)", Integer.class, course)).isEqualTo(active);
        // The current-generation worker can still finish the ticket.
        WaitingTicket current = tickets.findById(id).orElseThrow();
        enrollmentHandler.handle(current, current.getGenerationNo());
        assertThat(sql.queryForMap("SELECT status_cd, result_cd FROM tickets WHERE ticket_id=?", id))
            .containsEntry("status_cd", "SUCCEEDED").containsEntry("result_cd", "OK");
        assertThat(sql.queryForObject("SELECT count(*) FROM enrollments WHERE course_inst_id=CAST(? AS uuid) AND enroll_status='ENROLLED'", Integer.class, course)).isEqualTo(1);
        assertThat(sql.queryForObject("SELECT active_cnt FROM courses WHERE course_inst_id=CAST(? AS uuid)", Integer.class, course)).isEqualTo(1);
    }
}
