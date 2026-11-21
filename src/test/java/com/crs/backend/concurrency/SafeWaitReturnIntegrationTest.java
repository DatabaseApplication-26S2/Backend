package com.crs.backend.concurrency;

import com.crs.backend.TestFixtures;
import com.crs.backend.adapter.out.persistence.jpa.WaitingTicketJpaRepository;
import com.crs.backend.application.TicketDispatcher;
import com.crs.backend.application.handler.EnrollmentTicketHandler;
import com.crs.backend.auth.CurrentUser;
import com.crs.backend.domain.entity.WaitingTicket;
import com.crs.backend.domain.enums.UserRole;
import com.crs.backend.queue.QueueProperties;
import com.crs.backend.queue.QueueAdmissionScheduler;
import com.crs.backend.settings.ServiceSettingsService;
import com.crs.backend.settings.ServiceSettingsUpdateRequest;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;

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
    @MockitoSpyBean EnrollmentTicketHandler enrollmentHandler;
    @Autowired WaitingTicketJpaRepository tickets;
    @Autowired ServiceSettingsService settings;
    @Autowired PlatformTransactionManager txManager;
    @Autowired RedissonClient redisson;
    @Autowired QueueProperties queueProperties;
    /** 주기 스케줄러의 첫 claim이 준비 중인 WAITING 티켓을 먼저 가져가지 않게 끈다. 명시 호출과 N 변경 뒤 kick은 그대로 동작한다 */
    @MockitoBean QueueAdmissionScheduler scheduler;

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

    /** 넘겨 둔 작업이 예외로 끝났으면 제한 시간까지 기다리지 않고 그 예외로 바로 실패시킨다. 조건 판정용으로 항상 false를 돌려준다 */
    private static boolean failOnDeferredError(List<Future<?>> deferred) {
        for (Future<?> future : deferred) {
            if (!future.isDone()) continue;
            try {
                future.get();
            } catch (Exception e) {
                throw new AssertionError("넘겨 둔 핸들러 작업이 실패했다", e);
            }
        }
        return false;
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
                // claim은 핸들러 완료를 기다리지 않는다. B의 작업이 끝나(잠금에 막히지 않고 복귀) 추적이 풀릴 때까지 제한 시간 안에서 기다린다
                assertThat(dispatcher.awaitIdle(java.time.Duration.ofSeconds(WAIT_SECONDS))).as("B의 작업이 과목 잠금에 막히지 않고 끝난다").isTrue();
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
        assertThat(dispatcher.awaitIdle(java.time.Duration.ofSeconds(WAIT_SECONDS))).as("B의 재배정 작업 종료").isTrue();

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

    @Test void t9_N3에서_1번_Y_2번_X_3번_X를_한_배치로_배정하면_3번은_횟수_증가없이_복귀하고_N을_내려도_1_2_3_순으로_끝난다() throws Exception {
        sql.update("UPDATE service_settings SET concurrency_limit=3");
        long t1 = waiting(courseY, CODE_Y);
        long t2 = waiting(courseX, CODE_X);
        long t3 = waiting(courseX, CODE_X);
        ExecutorService workerPool = pool();
        ExecutorService deferredPool = pool();

        // 계획 4절 (6) "claim한 티켓마다 작업 하나를 실행기에 넘긴다"를 흉내 낸다.
        // 1번·2번의 디스패처 호출은 별도 스레드로 넘기고(래치를 기다린 뒤 진짜 핸들러 실행) 디스패처에는 곧바로 돌려준다.
        // 3번의 디스패처 호출은 진짜 핸들러를 그 자리에서 실행한다
        Map<Long, CountDownLatch> latches = new ConcurrentHashMap<>();
        latches.put(t1, new CountDownLatch(1));
        latches.put(t2, new CountDownLatch(1));
        List<Future<?>> deferred = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            long id = ((WaitingTicket) invocation.getArgument(0)).getTicketId();
            CountDownLatch latch = latches.get(id);
            if (latch == null) {
                return invocation.callRealMethod();
            }
            deferred.add(deferredPool.submit(() -> {
                assertThat(latch.await(WAIT_SECONDS * 3, TimeUnit.SECONDS)).as("래치 해제 대기").isTrue();
                // 스파이의 callRealMethod는 @Transactional 프록시를 거치지 않는다(프록시는 디스패처 스레드에서 이미 끝났다).
                // 실행기 작업이 새 스레드에서 도는 상황과 같게 REQUIRES_NEW 트랜잭션을 직접 열어 진짜 핸들러를 부른다
                new TransactionTemplate(txManager, new DefaultTransactionDefinition(TransactionDefinition.PROPAGATION_REQUIRES_NEW))
                    .executeWithoutResult(status -> {
                        try {
                            invocation.callRealMethod();
                        } catch (RuntimeException | Error e) {
                            throw e;
                        } catch (Throwable e) {
                            throw new IllegalStateException(e);
                        }
                    });
                return null;
            }));
            return null;
        }).when(enrollmentHandler).handle(any(), anyInt());

        try {
            // N=3이므로 한 번의 claim이 1·2·3번을 모두 잡는다. 3번 핸들러는 2번이 미완료라 곧바로 WAITING으로 복귀한다
            assertThat(within(workerPool, dispatcher::claimAndDispatch)).as("한 배치로 3건 claim").isEqualTo(3);
            // 1·2번은 스파이가 곧바로 돌려보내고 3번은 복귀한다. 세 작업이 모두 끝날 때까지 기다린 뒤 상태를 읽는다
            assertThat(dispatcher.awaitIdle(java.time.Duration.ofSeconds(WAIT_SECONDS))).as("세 작업 종료").isTrue();

            assertThat(row(t1).get("status_cd")).as("1번은 미완료, 슬롯 보유").isEqualTo("PROCESSING");
            assertThat(row(t2).get("status_cd")).as("2번은 미완료, 슬롯 보유").isEqualTo("PROCESSING");
            assertThat(processingCount()).isEqualTo(2);
            Map<String, Object> returned = row(t3);
            assertThat(returned.get("status_cd")).isEqualTo("WAITING");
            assertThat(returned.get("result_cd")).isNull();
            assertThat(returned.get("generation_no")).as("claim 때의 세대 그대로").isEqualTo(1);
            assertThat(returned.get("retry_count")).as("횟수 증가 없음").isEqualTo(0);
            assertThat(returned.get("owner_worker_ref")).isNull();
            assertThat(returned.get("lease_info")).isNull();
            assertThat(returned.get("slot_claim_ref")).isNull();
            assertThat(returned.get("processing_started_at")).isNull();
            assertThat(returned.get("processing_finished_at")).isNull();

            // 1번·2번이 미완료인 동안 N을 1로 내린다. 호출이 제한 시간 안에 끝나야 한다. N′ = 0이므로 3번은 claim되지 않는다
            within(workerPool, () -> settings.update(ADMIN_USER, new ServiceSettingsUpdateRequest(1)));
            assertThat(sql.queryForObject("SELECT concurrency_limit FROM service_settings", Integer.class)).isEqualTo(1);
            assertThat(within(workerPool, dispatcher::claimAndDispatch)).as("A=2 ≥ N=1: claim 없음").isZero();
            assertThat(row(t3).get("status_cd")).isEqualTo("WAITING");
            assertThat(row(t3).get("generation_no")).isEqualTo(1);
            assertThat(processingCount()).isEqualTo(2);

            // 1번 처리 허용: 1번 종결. 2번이 아직 슬롯을 쥐므로 A=1 ≥ N=1이고 claim은 여전히 0이다
            latches.get(t1).countDown();
            await("1번 종결", () -> failOnDeferredError(deferred) || "SUCCEEDED".equals(row(t1).get("status_cd")));
            assertThat(row(t2).get("status_cd")).isEqualTo("PROCESSING");
            assertThat(within(workerPool, dispatcher::claimAndDispatch)).as("A=1 ≥ N=1: claim 없음").isZero();
            assertThat(row(t3).get("status_cd")).isEqualTo("WAITING");

            // 2번 처리 허용: 2번 종결
            latches.get(t2).countDown();
            await("2번 종결", () -> failOnDeferredError(deferred) || "SUCCEEDED".equals(row(t2).get("status_cd")));

            // A=0이므로 다음 claim이 3번을 다시 잡는다. N 변경 뒤 DispatchKick이 먼저 잡았을 수 있어 반환값은 단정하지 않고 종결만 기다린다
            within(workerPool, dispatcher::claimAndDispatch);
            assertThat(dispatcher.awaitIdle(java.time.Duration.ofSeconds(WAIT_SECONDS))).as("3번 재배정 작업 종료").isTrue();
            await("3번 종결", () -> failOnDeferredError(deferred) || "SUCCEEDED".equals(row(t3).get("status_cd")));
        } finally {
            latches.values().forEach(CountDownLatch::countDown);
        }
        for (Future<?> future : deferred) future.get(WAIT_SECONDS * 3, TimeUnit.SECONDS);

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
