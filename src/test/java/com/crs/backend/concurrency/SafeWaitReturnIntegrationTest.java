package com.crs.backend.concurrency;

import com.crs.backend.TestFixtures;
import com.crs.backend.adapter.out.persistence.jpa.WaitingTicketJpaRepository;
import com.crs.backend.application.TicketDispatcher;
import com.crs.backend.application.handler.EnrollmentTicketHandler;
import com.crs.backend.auth.CurrentUser;
import com.crs.backend.domain.entity.WaitingTicket;
import com.crs.backend.domain.enums.UserRole;
import com.crs.backend.queue.QueueProperties;
import com.crs.backend.settings.ServiceSettingsService;
import com.crs.backend.settings.ServiceSettingsUpdateRequest;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
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
 * BE-17 안전 대기 복귀 통합 테스트(구현계획 4절 (4) 2번, (8) 5번, 7절 T9). 실제 PostgreSQL 위에서 돈다.
 * 같은 과목에 더 작은 번호의 미완료 티켓이 있으면 뒤 번호 티켓은 과목 잠금을 기다리지 않고
 * 번호·세대·재투입 횟수를 그대로 둔 채 곧바로 WAITING으로 돌아가야 한다.
 * 백그라운드 스케줄은 src/test/resources/config/application.properties가 꺼 두므로 메서드를 직접 호출한다.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(properties = {
    "queue.scheduler-fixed-delay-ms=3600000",
    "spring.datasource.hikari.maximum-pool-size=20"
})
@Import(SafeWaitReturnIntegrationTest.Database.class)
class SafeWaitReturnIntegrationTest {
    @Container
    static final GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("queue.waiting-key", () -> "safe-wait-test:waiting");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Database {
        @Bean @ServiceConnection
        PostgreSQLContainer postgres() {
            return new PostgreSQLContainer(DockerImageName.parse("postgres:17"));
        }
    }

    private static final String CODE_X = "SAFEX001";
    private static final String CODE_Y = "SAFEY001";
    private static final String ADMIN = "safeadmin";
    private static final CurrentUser ADMIN_USER = new CurrentUser(ADMIN, "s", UserRole.ADMIN);
    private static final long WAIT_SECONDS = 10;

    @Autowired JdbcTemplate sql;
    @Autowired JdbcClient jdbc;
    @Autowired DataSource dataSource;
    @Autowired TicketDispatcher dispatcher;
    @Autowired EnrollmentTicketHandler enrollmentHandler;
    @Autowired WaitingTicketJpaRepository tickets;
    @Autowired ServiceSettingsService settings;
    @Autowired RedissonClient redisson;
    @Autowired QueueProperties queueProperties;

    private String courseX;
    private String courseY;
    private int userSeq;
    private final List<ExecutorService> pools = new ArrayList<>();

    @BeforeEach void setup() {
        sql.execute("TRUNCATE enrollments, refresh_tokens, sessions, tickets, courses, users CASCADE");
        sql.update("INSERT INTO users VALUES ('prof', 'hash', 'Professor', 'PROFESSOR')");
        sql.update("INSERT INTO users VALUES (?, 'hash', 'Admin', 'ADMIN')", ADMIN);
        sql.update("""
            INSERT INTO service_settings(singleton, concurrency_limit, last_changed_by, last_changed_at)
            VALUES (true, 100, NULL, NULL)
            ON CONFLICT (singleton) DO UPDATE SET concurrency_limit=100,
                last_changed_by=NULL, last_changed_at=NULL
            """);
        courseX = course(CODE_X);
        courseY = course(CODE_Y);
        userSeq = 0;
        redisson.getScoredSortedSet(queueProperties.getWaitingKey()).delete();
    }

    @AfterEach void cleanup() throws InterruptedException {
        for (ExecutorService pool : pools) pool.shutdownNow();
        for (ExecutorService pool : pools) {
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).as("스레드 정리").isTrue();
        }
        pools.clear();
        redisson.getScoredSortedSet(queueProperties.getWaitingKey()).delete();
    }

    private String course(String code) {
        String id = UUID.randomUUID().toString();
        sql.update("""
            INSERT INTO courses(course_inst_id,course_code,professor_id,course_title,course_category,
              grading_type,max_capacity,min_enrollment,classroom,course_created_at)
            VALUES (CAST(? AS uuid),?,'prof','Course','MAJOR','ABSOLUTE',5,1,'Room',clock_timestamp())
            """, id, code);
        return id;
    }

    private ExecutorService pool() {
        ExecutorService pool = Executors.newCachedThreadPool();
        pools.add(pool);
        return pool;
    }

    /** 학생 1명(사용자당 미완료 티켓 1건)과 입장 처리된 세션을 만들고 [사용자, 세션]을 돌려준다 */
    private String[] student() {
        String user = "safe" + (userSeq++);
        sql.update("INSERT INTO users VALUES (?, 'hash', ?, 'STUDENT')", user, user);
        String session = TestFixtures.session(jdbc, user, "STUDENT");
        sql.update("UPDATE sessions SET entered_at=clock_timestamp() WHERE session_id=CAST(? AS uuid)", session);
        return new String[] {user, session};
    }

    /** 접수 직후 WAITING인 신청 티켓. 생성 순서가 곧 티켓 번호 순서다 */
    private long waiting(String courseInstId, String code) {
        String[] s = student();
        return TestFixtures.waitingEnrollmentTicket(jdbc, s[0], s[1], courseInstId, code);
    }

    /** 이미 다른 처리기가 PROCESSING으로 쥐고 있는 티켓(세대 1, 리스 20초 남음, CHECK 3 충족) */
    private long processing(String courseInstId, String code) {
        String[] s = student();
        return jdbc.sql("""
                INSERT INTO tickets (request_type, requester_user_id, session_ref, target_course_inst_id,
                                     course_code_snapshot, ticket_issued_at, status_cd, processing_started_at,
                                     retry_count, owner_worker_ref, generation_no, lease_info, slot_claim_ref)
                VALUES ('ENROLLMENT', :user, CAST(:session AS uuid), CAST(:course AS uuid), :code,
                        clock_timestamp() - interval '1 minute', 'PROCESSING', clock_timestamp(),
                        0, :owner, 1, clock_timestamp() + interval '20 seconds', 1)
                RETURNING ticket_id""")
            .param("user", s[0]).param("session", s[1]).param("course", courseInstId).param("code", code)
            .param("owner", WaitingTicket.WORKER_REF)
            .query(Long.class).single();
    }

    private Map<String, Object> row(long ticketId) {
        return sql.queryForMap("""
            SELECT status_cd, result_cd, retry_count, generation_no, owner_worker_ref, lease_info,
                   slot_claim_ref, processing_started_at, processing_finished_at
            FROM tickets WHERE ticket_id=?""", ticketId);
    }

    private int processingCount() {
        return sql.queryForObject("SELECT count(*) FROM tickets WHERE status_cd='PROCESSING'", Integer.class);
    }

    private int activeCnt(String courseInstId) {
        return sql.queryForObject("SELECT active_cnt FROM courses WHERE course_inst_id=CAST(? AS uuid)",
            Integer.class, courseInstId);
    }

    private boolean finishedBefore(long first, long second) {
        return sql.queryForObject("""
            SELECT (SELECT processing_finished_at FROM tickets WHERE ticket_id=?)
                 < (SELECT processing_finished_at FROM tickets WHERE ticket_id=?)""", Boolean.class, first, second);
    }

    /** 다른 연결에서 과목 행을 FOR NO KEY UPDATE로 잡는다. 워커의 과목 잠금(courseLock)과 같은 모드다 */
    private int lockCourse(Connection connection, String courseInstId) throws Exception {
        connection.setAutoCommit(false);
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT 1 FROM courses WHERE course_inst_id=CAST('"
                 + courseInstId + "' AS uuid) FOR NO KEY UPDATE")) {
            assertThat(result.next()).as("과목 행 잠금 획득").isTrue();
        }
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT pg_backend_pid()")) {
            result.next();
            return result.getInt(1);
        }
    }

    private int blockedBy(int holderPid) {
        return sql.queryForObject(
            "SELECT count(*) FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))", Integer.class, holderPid);
    }

    private void await(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).as("제한 시간 안에 " + what).isLessThan(deadline);
            Thread.sleep(50);
        }
    }

    private <T> T within(ExecutorService pool, Callable<T> call) throws Exception {
        Future<T> future = pool.submit(call);
        return future.get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    @Test void t9_같은_과목의_앞_티켓이_처리중이면_뒤_티켓은_과목_잠금을_기다리지_않고_대기로_돌아가고_앞이_먼저_끝난다() throws Exception {
        long a = processing(courseX, CODE_X);
        long b = waiting(courseX, CODE_X);
        WaitingTicket snapshotA = tickets.findById(a).orElseThrow();
        ExecutorService pool = pool();

        try (Connection holder = dataSource.getConnection()) {
            lockCourse(holder, courseX);
            try {
                // B를 claim해 워커를 돌린다. 과목 X 잠금을 다른 연결이 쥐고 있어도 B는 그 잠금에 닿지 않고 끝나야 한다
                int claimed = within(pool, dispatcher::claimAndDispatch);

                assertThat(claimed).as("B만 claim").isEqualTo(1);
                Map<String, Object> returned = row(b);
                assertThat(returned.get("status_cd")).isEqualTo("WAITING");
                assertThat(returned.get("result_cd")).isNull();
                assertThat(returned.get("generation_no")).as("claim 때의 세대(0→1) 그대로, 복귀가 올리지 않는다").isEqualTo(1);
                assertThat(returned.get("retry_count")).isEqualTo(0);
                assertThat(returned.get("owner_worker_ref")).isNull();
                assertThat(returned.get("lease_info")).isNull();
                assertThat(returned.get("slot_claim_ref")).isNull();
                assertThat(returned.get("processing_started_at")).isNull();
                assertThat(returned.get("processing_finished_at")).isNull();
                assertThat(sql.queryForObject("SELECT count(*) FROM tickets WHERE request_type='ENROLLMENT'", Integer.class))
                    .as("새 티켓 없이 같은 번호로 복귀").isEqualTo(2);
                assertThat(processingCount()).as("B의 슬롯은 반납, A만 남음").isEqualTo(1);
                assertThat(row(a).get("status_cd")).isEqualTo("PROCESSING");
                assertThat(sql.queryForObject("SELECT count(*) FROM enrollments", Integer.class)).isZero();
                assertThat(redisson.getScoredSortedSet(queueProperties.getWaitingKey()).contains(String.valueOf(b)))
                    .as("커밋 뒤 같은 번호로 Redis 대기 순서에 다시 올라간다").isTrue();
            } finally {
                holder.rollback();
            }
        }

        // A가 먼저 끝난다(실제 핸들러, 소유 세대 1)
        enrollmentHandler.handle(snapshotA, 1);
        assertThat(row(a).get("status_cd")).isEqualTo("SUCCEEDED");
        assertThat(row(a).get("result_cd")).isEqualTo("OK");

        // 다음 claim에서 B가 다시 잡혀 끝난다
        assertThat(dispatcher.claimAndDispatch()).isEqualTo(1);

        Map<String, Object> done = row(b);
        assertThat(done.get("status_cd")).isEqualTo("SUCCEEDED");
        assertThat(done.get("result_cd")).isEqualTo("OK");
        assertThat(done.get("generation_no")).as("재배정으로 세대 +1").isEqualTo(2);
        assertThat(done.get("retry_count")).as("안전 대기 복귀는 재투입 횟수를 올리지 않는다").isEqualTo(0);
        assertThat(done.get("owner_worker_ref")).isNull();
        assertThat(done.get("slot_claim_ref")).isNull();
        assertThat(finishedBefore(a, b)).as("A의 종결 시각 < B의 종결 시각").isTrue();
        assertThat(sql.queryForObject("SELECT count(*) FROM enrollments WHERE enroll_status='ENROLLED'", Integer.class))
            .isEqualTo(2);
        assertThat(activeCnt(courseX)).isEqualTo(2);
        assertThat(processingCount()).isZero();
    }

    @Test void t9_N3에서_1번_Y_2번_X_3번_X를_배정하면_3번은_횟수_증가없이_복귀하고_N을_내려도_1_2_3_순으로_끝난다() throws Exception {
        sql.update("UPDATE service_settings SET concurrency_limit=3");
        long t1 = waiting(courseY, CODE_Y);
        long t2 = waiting(courseX, CODE_X);
        long t3 = waiting(courseX, CODE_X);
        ExecutorService batchPool = pool();
        ExecutorService workerPool = pool();

        try (Connection holder = dataSource.getConnection()) {
            int holderPid = lockCourse(holder, courseX);
            // 디스패처는 배치를 한 스레드에서 차례로 돌린다. 1번은 끝나고 2번은 과목 X 잠금에서 멈춘다
            Future<Integer> batch = batchPool.submit(dispatcher::claimAndDispatch);
            try {

                await("2번 워커가 과목 X 잠금에서 대기", () -> blockedBy(holderPid) == 1);
                assertThat(row(t1).get("status_cd")).as("1번(Y)은 먼저 종결").isEqualTo("SUCCEEDED");
                assertThat(row(t2).get("status_cd")).as("2번은 진행 중").isEqualTo("PROCESSING");
                assertThat(row(t3).get("status_cd")).isEqualTo("PROCESSING");
                int gen3 = (int) row(t3).get("generation_no");
                assertThat(gen3).isEqualTo(1);
                WaitingTicket snapshot3 = tickets.findById(t3).orElseThrow();

                // 3번 워커를 별도 스레드에서 돌린다. 2번이 미완료이므로 과목 잠금(X는 남이 쥠)에 닿지 않고 바로 복귀한다
                within(workerPool, () -> {
                    enrollmentHandler.handle(snapshot3, gen3);
                    return null;
                });

                Map<String, Object> returned = row(t3);
                assertThat(returned.get("status_cd")).isEqualTo("WAITING");
                assertThat(returned.get("result_cd")).isNull();
                assertThat(returned.get("generation_no")).isEqualTo(gen3);
                assertThat(returned.get("retry_count")).as("횟수 증가 없음").isEqualTo(0);
                assertThat(returned.get("owner_worker_ref")).isNull();
                assertThat(returned.get("lease_info")).isNull();
                assertThat(returned.get("slot_claim_ref")).isNull();
                assertThat(returned.get("processing_started_at")).isNull();
                assertThat(blockedBy(holderPid)).as("2번은 여전히 대기, 3번은 잠금을 기다리지 않음").isEqualTo(1);

                // N을 1로 내린다. 진행 중인 2번이 슬롯을 쥐므로 N′ = 0이고 3번은 아직 claim되지 않는다
                settings.update(ADMIN_USER, new ServiceSettingsUpdateRequest(1));
                assertThat(sql.queryForObject("SELECT concurrency_limit FROM service_settings", Integer.class)).isEqualTo(1);
                assertThat(processingCount()).as("처리 중인 2번만 남음").isEqualTo(1);
                assertThat(row(t3).get("status_cd")).isEqualTo("WAITING");
                assertThat(row(t3).get("generation_no")).isEqualTo(gen3);
            } finally {
                holder.rollback(); // 과목 X 잠금 해제: 2번이 이어서 끝난다
            }
            assertThat(batch.get(WAIT_SECONDS * 3, TimeUnit.SECONDS)).as("한 배치로 3건 claim").isEqualTo(3);
        }

        assertThat(row(t2).get("status_cd")).isEqualTo("SUCCEEDED");
        assertThat(row(t2).get("result_cd")).isEqualTo("OK");

        // 배치의 3번 호출은 상태가 WAITING이라 아무 일도 하지 않았고, 이제 N=1·처리 중 0이므로 다음 claim이 3번을 잡는다
        dispatcher.claimAndDispatch();
        await("3번 종결", () -> "SUCCEEDED".equals(row(t3).get("status_cd")));

        for (long id : new long[] {t1, t2, t3}) {
            assertThat(row(id).get("status_cd")).isEqualTo("SUCCEEDED");
            assertThat(row(id).get("result_cd")).isEqualTo("OK");
            assertThat(row(id).get("retry_count")).as("어느 티켓도 재투입 횟수가 오르지 않음").isEqualTo(0);
        }
        assertThat(row(t3).get("generation_no")).as("3번은 두 번 claim").isEqualTo(2);
        assertThat(finishedBefore(t1, t2)).as("1번 < 2번").isTrue();
        assertThat(finishedBefore(t2, t3)).as("2번 < 3번").isTrue();
        assertThat(activeCnt(courseX)).isEqualTo(2);
        assertThat(activeCnt(courseY)).isEqualTo(1);
        assertThat(sql.queryForObject("SELECT count(*) FROM enrollments WHERE enroll_status='ENROLLED'", Integer.class))
            .isEqualTo(3);
        assertThat(processingCount()).isZero();
    }
}
