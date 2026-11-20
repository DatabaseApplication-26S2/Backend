package com.crs.backend.concurrency;

import com.crs.backend.TestFixtures;
import com.crs.backend.adapter.in.web.dto.TicketReceipt;
import com.crs.backend.application.TicketIntakeService;
import com.crs.backend.auth.AuthService;
import com.crs.backend.domain.exception.EnrollmentException;
import com.crs.backend.domain.exception.ErrorCode;
import com.crs.backend.queue.QueueProperties;
import java.sql.Connection;
import java.sql.Timestamp;
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
import org.redisson.api.RedissonClient;
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

/**
 * 전역 접수 잠금 통합 테스트(plan/intake-lock.md C1). 실제 PostgreSQL 위에서 돈다.
 * 같은 사용자의 이중 접수는 202 1건과 E03 1건이어야 하고(AT-03), 접수 시각은 잠금을 얻은 뒤의 DB 시계라
 * 번호 순서를 따라야 한다(AT-04). 백그라운드 스케줄은 꺼 두었고 티켓은 접수 서비스를 직접 호출해 만든다.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(properties = {
    "queue.scheduler-fixed-delay-ms=3600000",
    "spring.datasource.hikari.maximum-pool-size=70"
})
@Import(IntakeLockIntegrationTest.Database.class)
class IntakeLockIntegrationTest {
    @Container
    static final GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("queue.waiting-key", () -> "intake-lock-test:waiting");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Database {
        @Bean @ServiceConnection
        PostgreSQLContainer postgres() {
            return new PostgreSQLContainer(DockerImageName.parse("postgres:17"));
        }
    }

    private static final String CODE = "INTAKE01";
    private static final int ROUNDS = 20;

    @Autowired JdbcTemplate sql;
    @Autowired JdbcClient jdbc;
    @Autowired DataSource dataSource;
    @Autowired TicketIntakeService intake;
    @Autowired RedissonClient redisson;
    @Autowired QueueProperties queueProperties;

    private String course;
    private final List<String> sessions = new ArrayList<>();

    @BeforeEach void setup() {
        sql.execute("TRUNCATE enrollments, refresh_tokens, sessions, tickets, courses, users CASCADE");
        redisson.getScoredSortedSet(queueProperties.getWaitingKey()).delete();
        sql.update("INSERT INTO users VALUES ('prof', 'hash', 'Professor', 'PROFESSOR')");
        course = UUID.randomUUID().toString();
        sql.update("""
            INSERT INTO courses(course_inst_id,course_code,professor_id,course_title,course_category,
              grading_type,max_capacity,min_enrollment,classroom,course_created_at)
            VALUES (CAST(? AS uuid),?,'prof','Course','MAJOR','ABSOLUTE',100,1,'Room',clock_timestamp())
            """, course, CODE);
        sessions.clear();
    }

    /** 입장한 학생 count명(student0..)과 각자의 세션을 만든다 */
    private void students(int count) {
        for (int i = 0; i < count; i++) {
            String user = "student" + i;
            sql.update("INSERT INTO users VALUES (?, 'hash', ?, 'STUDENT')", user, user);
            String session = TestFixtures.session(jdbc, user, "STUDENT");
            sql.update("UPDATE sessions SET entered_at=clock_timestamp() WHERE session_id=CAST(? AS uuid)", session);
            sessions.add(session);
        }
    }

    private TicketReceipt submit(int student) {
        return intake.intakeEnrollment("student" + student, sessions.get(student), course, CODE);
    }

    /** 라운드마다 미완료 티켓을 비운다(WAITING → REJECTED/E05, tickets CHECK 2·4를 지킨다) */
    private void completeAllIncomplete() {
        sql.update("""
            UPDATE tickets SET status_cd='REJECTED', result_cd='E05', processing_finished_at=clock_timestamp()
            WHERE status_cd IN ('RECEIVED','WAITING')
            """);
        assertThat(incompleteCount()).isZero();
    }

    private int incompleteCount() {
        return sql.queryForObject("SELECT count(*) FROM tickets WHERE status_cd IN ('RECEIVED','WAITING','PROCESSING')", Integer.class);
    }

    /** 각 작업을 같은 출발선에서 동시에 시작하고, 영수증 또는 던진 예외를 입력 순서대로 돌려준다 */
    private List<Object> concurrently(List<java.util.concurrent.Callable<TicketReceipt>> jobs) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(jobs.size());
        CountDownLatch ready = new CountDownLatch(jobs.size()), start = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (var job : jobs) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    if (!start.await(30, TimeUnit.SECONDS)) throw new AssertionError("start gate timed out");
                    try {
                        return job.call();
                    } catch (Throwable t) {
                        return t;
                    }
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> future : futures) results.add(future.get(90, TimeUnit.SECONDS));
            return results;
        } finally {
            start.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    private Instant issuedAt(long ticketId) {
        return sql.queryForObject("SELECT ticket_issued_at FROM tickets WHERE ticket_id=?", Timestamp.class, ticketId).toInstant();
    }

    @Test void at03_같은_사용자의_동시_이중_접수는_성공_1건과_E03_1건이다() throws Exception {
        students(1);
        for (int round = 0; round < ROUNDS; round++) {
            List<Object> results = concurrently(List.of(() -> submit(0), () -> submit(0)));
            List<TicketReceipt> receipts = results.stream().filter(TicketReceipt.class::isInstance).map(TicketReceipt.class::cast).toList();
            List<Object> failures = results.stream().filter(r -> !(r instanceof TicketReceipt)).toList();
            assertThat(receipts).as("라운드 %d 성공 건수", round).hasSize(1);
            assertThat(failures).as("라운드 %d 실패 건수", round).hasSize(1);
            assertThat(failures.getFirst()).as("라운드 %d 실패 유형(다른 예외·유일 인덱스 위반 0건)", round)
                .isInstanceOf(EnrollmentException.class);
            EnrollmentException conflict = (EnrollmentException) failures.getFirst();
            assertThat(conflict.getErrorCode()).isEqualTo(ErrorCode.E03);
            assertThat(String.valueOf(conflict.getActiveTicketId())).as("activeTicketId = 성공한 티켓 번호")
                .isEqualTo(receipts.getFirst().ticketId());
            assertThat(incompleteCount()).as("라운드 %d 미완료 행", round).isEqualTo(1);
            assertThat(sql.queryForObject("SELECT status_cd FROM tickets WHERE ticket_id=?", String.class,
                Long.parseLong(receipts.getFirst().ticketId()))).isEqualTo("WAITING");
            completeAllIncomplete();
        }
        assertThat(sql.queryForObject("SELECT count(*) FROM tickets WHERE request_type='ENROLLMENT'", Integer.class))
            .as("20라운드 동안 만들어진 신청 티켓").isEqualTo(ROUNDS);
    }

    @Test void at03_미완료_신청이_있으면_취소_접수도_E03과_그_번호를_받는다() {
        students(1);
        long enrollmentTicket = Long.parseLong(submit(0).ticketId());
        assertThatCancellationConflicts(enrollmentTicket);
        assertThat(incompleteCount()).as("취소 접수가 새 행을 만들지 않았다").isEqualTo(1);
        assertThat(sql.queryForObject("SELECT count(*) FROM tickets WHERE request_type='CANCELLATION'", Integer.class)).isZero();
    }

    private void assertThatCancellationConflicts(long activeTicket) {
        Throwable thrown = null;
        try {
            intake.intakeCancellation("student0", sessions.get(0), course, CODE);
        } catch (Throwable t) {
            thrown = t;
        }
        assertThat(thrown).isInstanceOf(EnrollmentException.class);
        EnrollmentException conflict = (EnrollmentException) thrown;
        assertThat(conflict.getErrorCode()).isEqualTo(ErrorCode.E03);
        assertThat(conflict.getActiveTicketId()).isEqualTo(activeTicket);
    }

    @Test void at04_동시_접수의_접수_시각은_번호_순서를_따른다() throws Exception {
        students(51);
        List<java.util.concurrent.Callable<TicketReceipt>> jobs = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            final int student = i;
            jobs.add(() -> submit(student));
        }
        List<Object> results = concurrently(jobs);
        assertThat(results).as("접수증 50건, 예외 0건").allMatch(TicketReceipt.class::isInstance).hasSize(50);
        Map<Long, Instant> receiptIssuedAt = new java.util.HashMap<>();
        for (Object result : results) {
            TicketReceipt receipt = (TicketReceipt) result;
            receiptIssuedAt.put(Long.parseLong(receipt.ticketId()), receipt.submittedAt());
        }
        List<Map<String, Object>> rows = sql.queryForList(
            "SELECT ticket_id, ticket_issued_at FROM tickets WHERE request_type='ENROLLMENT' ORDER BY ticket_id");
        assertThat(rows).hasSize(50);
        Instant previous = null;
        for (Map<String, Object> row : rows) {
            long id = ((Number) row.get("ticket_id")).longValue();
            Instant issued = ((Timestamp) row.get("ticket_issued_at")).toInstant();
            if (previous != null) assertThat(issued).as("ticket %d 접수 시각이 앞 번호보다 이르지 않다", id).isAfterOrEqualTo(previous);
            assertThat(receiptIssuedAt.get(id)).as("접수증 issuedAt = DB ticket_issued_at (ticket %d)", id).isEqualTo(issued);
            previous = issued;
        }
        completeAllIncomplete();

        // (2) 접수 잠금을 다른 연결이 쥔 동안 51번째 학생이 접수를 시작한다. 접수 시각은 잠금을 얻은 뒤에 읽어야 한다
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            int holderPid;
            try (var statement = holder.createStatement()) {
                statement.execute("SELECT pg_advisory_xact_lock(" + AuthService.INTAKE_LOCK + ")");
                try (var pid = statement.executeQuery("SELECT pg_backend_pid()")) {
                    assertThat(pid.next()).isTrue();
                    holderPid = pid.getInt(1);
                }
            }
            Future<TicketReceipt> blocked = pool.submit(() -> submit(50));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            int waiters = 0;
            while (System.nanoTime() < deadline) {
                waiters = sql.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity
                    WHERE ? = ANY(pg_blocking_pids(pid)) AND wait_event_type='Lock' AND wait_event='advisory'
                    """, Integer.class, holderPid);
                if (waiters > 0) break;
                assertThat(blocked.isDone()).as("잠금을 쥔 동안 접수가 끝나면 안 된다").isFalse();
                Thread.sleep(20);
            }
            assertThat(waiters).as("51번째 접수가 접수 잠금에서 기다린다").isEqualTo(1);
            assertThat(blocked.isDone()).isFalse();
            Instant heldAt;
            try (var statement = holder.createStatement();
                 var now = statement.executeQuery("SELECT clock_timestamp()")) {
                assertThat(now.next()).isTrue();
                heldAt = now.getTimestamp(1).toInstant();
            }
            holder.commit();
            TicketReceipt receipt = blocked.get(30, TimeUnit.SECONDS);
            Instant issued = issuedAt(Long.parseLong(receipt.ticketId()));
            assertThat(issued).as("접수 시각이 잠금 보유 트랜잭션이 커밋 직전에 읽은 시각보다 늦다").isAfter(heldAt);
            assertThat(receipt.submittedAt()).isEqualTo(issued);
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }
}
