package com.crs.backend.concurrency;

import com.crs.backend.TestFixtures;
import com.crs.backend.application.TicketDispatcher;
import com.crs.backend.auth.CurrentUser;
import com.crs.backend.domain.enums.UserRole;
import com.crs.backend.queue.QueueProperties;
import com.crs.backend.settings.ServiceSettingsService;
import com.crs.backend.settings.ServiceSettingsUpdateRequest;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.CopyOnWriteArrayList;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;

/**
 * BE-17 하향 조정 통합 테스트(구현계획 4절 (3), 7절 T7: 처리 중 N 5→1).
 * 실제 PostgreSQL 위에서, 진행 중 작업 5건이 과목 행 잠금에 막혀 있는 동안 N을 1로 낮춘다.
 * 하향 후에는 신규 claim만 멈추고 활성 작업(A = PROCESSING 수)은 끝까지 간다.
 *
 * 관찰 방법
 * - 막힘: 별도 JDBC 연결이 과목 행을 FOR NO KEY UPDATE로 쥐고, 관찰 연결이 pg_blocking_pids로 실제 잠금 대기 그래프를 본다.
 * - A: tickets에서 status_cd='PROCESSING' 행 수를 센다.
 * - 디스패처는 claim한 배치를 한 스레드에서 차례로 실행하므로 막히는 핸들러는 한 번에 하나이고,
 *   잠금은 티켓 번호 순서대로 풀어야 다음 핸들러가 시작한다.
 * 백그라운드 스케줄은 src/test/resources/config/application.properties가 꺼 두므로 claim은 이 테스트가 직접 부른다.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(properties = {
    "queue.scheduler-fixed-delay-ms=3600000",
    "spring.datasource.hikari.maximum-pool-size=20"
})
@Import(ThrottleDownIntegrationTest.Database.class)
class ThrottleDownIntegrationTest {
    @Container
    static final GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("queue.waiting-key", () -> "throttle-down-test:waiting");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Database {
        @Bean @ServiceConnection
        PostgreSQLContainer postgres() {
            return new PostgreSQLContainer(DockerImageName.parse("postgres:17"));
        }
    }

    private static final int IN_FLIGHT = 5;
    private static final int EXTRA = 3;
    private static final int WAIT_SECONDS = 15;
    private static final String ADMIN = "thradmin";
    private static final CurrentUser ADMIN_USER = new CurrentUser(ADMIN, "s", UserRole.ADMIN);

    @Autowired JdbcTemplate sql;
    @Autowired JdbcClient jdbc;
    @Autowired DataSource dataSource;
    @MockitoSpyBean TicketDispatcher dispatcher;
    @Autowired ServiceSettingsService settings;
    @Autowired RedissonClient redisson;
    @Autowired QueueProperties queueProperties;

    private final List<String> courses = new ArrayList<>();
    private final List<Long> ticketIds = new ArrayList<>();

    private static final String KICK_THREAD = "settings-dispatch-kick";
    /** claimAndDispatch 호출이 끝날 때마다 {호출 스레드 이름, 반환값}을 쌓는다 */
    private final List<Object[]> claimCalls = new CopyOnWriteArrayList<>();

    private List<Integer> kickResults() {
        List<Integer> results = new ArrayList<>();
        for (Object[] call : claimCalls) {
            if (KICK_THREAD.equals(call[0])) results.add((Integer) call[1]);
        }
        return results;
    }

    @BeforeEach void setup() {
        claimCalls.clear();
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            claimCalls.add(new Object[] {Thread.currentThread().getName(), result});
            return result;
        }).when(dispatcher).claimAndDispatch();
        sql.execute("TRUNCATE enrollments, refresh_tokens, sessions, tickets, courses, users CASCADE");
        sql.update("""
            INSERT INTO service_settings(singleton, concurrency_limit, last_changed_by, last_changed_at)
            VALUES (true, 5, NULL, NULL)
            ON CONFLICT (singleton) DO UPDATE SET concurrency_limit=5,
                last_changed_by=NULL, last_changed_at=NULL
            """);
        sql.update("INSERT INTO users VALUES ('prof', 'hash', 'Professor', 'PROFESSOR')");
        sql.update("INSERT INTO users VALUES (?, 'hash', 'Admin', 'ADMIN')", ADMIN);
        courses.clear();
        ticketIds.clear();
        // 서로 다른 OPEN 과목 8개, 서로 다른 입장 완료 학생 8명, 과목마다 WAITING 신청 티켓 1건(번호는 만든 순서대로 증가)
        for (int i = 0; i < IN_FLIGHT + EXTRA; i++) {
            String course = UUID.randomUUID().toString();
            String code = "THR" + String.format("%03d", i);
            sql.update("""
                INSERT INTO courses(course_inst_id,course_code,professor_id,course_title,course_category,
                  grading_type,max_capacity,min_enrollment,classroom,course_created_at)
                VALUES (CAST(? AS uuid),?,'prof','Course','MAJOR','ABSOLUTE',3,1,'Room',clock_timestamp())
                """, course, code);
            String user = "thr" + i;
            sql.update("INSERT INTO users VALUES (?, 'hash', ?, 'STUDENT')", user, user);
            String session = TestFixtures.session(jdbc, user, "STUDENT");
            sql.update("UPDATE sessions SET entered_at=clock_timestamp() WHERE session_id=CAST(? AS uuid)", session);
            courses.add(course);
            ticketIds.add(TestFixtures.waitingEnrollmentTicket(jdbc, user, session, course, code));
        }
        redisson.getScoredSortedSet(queueProperties.getWaitingKey()).delete();
    }

    @AfterEach void cleanup() {
        sql.update("UPDATE service_settings SET concurrency_limit=100 WHERE singleton");
        redisson.getScoredSortedSet(queueProperties.getWaitingKey()).delete();
    }

    private Map<String, Object> row(long ticketId) {
        return sql.queryForMap("""
            SELECT status_cd, result_cd, retry_count, generation_no, owner_worker_ref, slot_claim_ref,
                   processing_started_at, processing_finished_at
            FROM tickets WHERE ticket_id=?""", ticketId);
    }

    private String status(long ticketId) {
        return (String) row(ticketId).get("status_cd");
    }

    private int processingCount() {
        return sql.queryForObject("SELECT count(*) FROM tickets WHERE status_cd='PROCESSING'", Integer.class);
    }

    private int limit() {
        return sql.queryForObject("SELECT concurrency_limit FROM service_settings WHERE singleton", Integer.class);
    }

    /** 제한 시간 안에 조건이 참이 될 때까지 기다린다. 가드 작업이 먼저 실패하면 그 예외를 그대로 올린다 */
    private void awaitUntil(String what, BooleanSupplier condition, Future<?> guard) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            if (guard.isDone()) guard.get(1, TimeUnit.SECONDS); // 실패면 ExecutionException으로 원인을 드러낸다
            Thread.sleep(10);
        }
        assertThat(condition.getAsBoolean()).as("제한 시간 안에 조건 충족: " + what).isTrue();
    }

    /** blockerPid가 쥔 잠금을 기다리는 백엔드가 있는지 pg_blocking_pids로 본다 */
    private boolean blockedBy(Connection observer, int blockerPid) throws Exception {
        try (var clear = observer.createStatement();
             var probe = observer.prepareStatement("""
                 SELECT EXISTS (
                     SELECT 1 FROM pg_stat_activity
                     WHERE datname=current_database() AND ? = ANY(pg_blocking_pids(pid)))""")) {
            clear.execute("SELECT pg_stat_clear_snapshot()");
            probe.setInt(1, blockerPid);
            try (var result = probe.executeQuery()) {
                result.next();
                return result.getBoolean(1);
            }
        }
    }

    private void awaitBlockedBy(Connection observer, int blockerPid, String what, Future<?> guard) throws Exception {
        awaitUntil(what, () -> {
            try {
                return blockedBy(observer, blockerPid);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }, guard);
    }

    /** 다른 스레드에서 claim을 부른다. 진행 중 작업이 남아 있으면 아무것도 claim하지 않고 제한 시간 안에 0으로 돌아와야 한다 */
    private void assertClaimNothing(ExecutorService pool, String when) throws Exception {
        Future<Integer> probe = pool.submit(dispatcher::claimAndDispatch);
        assertThat(probe.get(10, TimeUnit.SECONDS)).as(when + ": A ≥ 1이면 신규 claim 0").isZero();
    }

    /** 신규 claim이 없다: 여분 티켓은 WAITING 그대로(상태·횟수·세대·소유자 불변), 진행 중 PROCESSING 수는 expectedA */
    private void assertExtrasUntouched(int expectedA, String when) {
        assertThat(processingCount()).as(when + ": A").isEqualTo(expectedA);
        for (int i = IN_FLIGHT; i < IN_FLIGHT + EXTRA; i++) {
            Map<String, Object> extra = row(ticketIds.get(i));
            assertThat(extra.get("status_cd")).as(when + ": 여분 티켓 " + i).isEqualTo("WAITING");
            assertThat(extra.get("retry_count")).isEqualTo(0);
            assertThat(extra.get("generation_no")).isEqualTo(0);
            assertThat(extra.get("owner_worker_ref")).isNull();
            assertThat(extra.get("slot_claim_ref")).isNull();
            assertThat(extra.get("processing_started_at")).isNull();
        }
    }

    /** 일정 시간 동안 신규 claim이 생기지 않고 상태가 그대로인지 지켜본다(N 변경 뒤 깨우기 스레드의 claim을 포함) */
    private void observeStable(int expectedA, String when) throws Exception {
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1000);
        while (System.nanoTime() < until) {
            assertExtrasUntouched(expectedA, when);
            Thread.sleep(50);
        }
    }

    @Test void t7_N을_5에서_1로_낮추면_진행_중_작업은_끝까지_가고_A가_0이_되어야_신규_claim이_생긴다() throws Exception {
        List<Connection> holders = new ArrayList<>();
        List<Integer> holderPids = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try (Connection observer = dataSource.getConnection()) {
            observer.setAutoCommit(true);
            // 진행 중 과목 5개를 서로 다른 연결로 잠근다. 핸들러가 과목 행 잠금에서 막힌다
            for (int i = 0; i < IN_FLIGHT; i++) {
                Connection holder = dataSource.getConnection();
                holders.add(holder);
                holder.setAutoCommit(false);
                try (var statement = holder.createStatement();
                     var result = statement.executeQuery(
                         "SELECT 1 FROM courses WHERE course_inst_id='" + courses.get(i) + "' FOR NO KEY UPDATE")) {
                    assertThat(result.next()).as("과목 행 잠금 획득 " + i).isTrue();
                }
                try (var statement = holder.createStatement();
                     var result = statement.executeQuery("SELECT pg_backend_pid()")) {
                    result.next();
                    holderPids.add(result.getInt(1));
                }
            }

            // N=5로 5건 claim. 첫 핸들러가 막혀 있으므로 호출은 끝나지 않고 5건은 PROCESSING에 머문다(A=5)
            Future<Integer> flight = pool.submit(dispatcher::claimAndDispatch);
            awaitUntil("A=5", () -> processingCount() == IN_FLIGHT, flight);
            awaitBlockedBy(observer, holderPids.get(0), "첫 핸들러가 과목 잠금 대기", flight);
            assertThat(flight.isDone()).as("핸들러가 막혀 있는 동안 claim 호출은 끝나지 않는다").isFalse();
            List<Long> inFlight = ticketIds.subList(0, IN_FLIGHT);
            long[] generation = new long[IN_FLIGHT];
            for (int i = 0; i < IN_FLIGHT; i++) {
                Map<String, Object> r = row(inFlight.get(i));
                assertThat(r.get("status_cd")).as("가장 작은 번호 5건이 claim된다").isEqualTo("PROCESSING");
                assertThat(r.get("retry_count")).isEqualTo(0);
                generation[i] = ((Number) r.get("generation_no")).longValue();
                assertThat(generation[i]).isEqualTo(1L);
            }
            assertExtrasUntouched(IN_FLIGHT, "하향 전");

            // 진행 중에 N을 1로 낮춘다(설정 변경은 회계 잠금만 쥐므로 막힌 핸들러와 무관하게 끝난다)
            assertThat(settings.update(ADMIN_USER, new ServiceSettingsUpdateRequest(1)).concurrencyLimit()).isEqualTo(1);
            assertThat(limit()).isEqualTo(1);
            // 커밋 뒤 깨우기 스레드(settings-dispatch-kick)의 claim이 실제로 끝날 때까지 기다리고, 0을 돌려줬는지 확인한다.
            // 그래야 이후 잠금 해제와 겹쳐 뒤늦게 claim이 도는 일이 없다
            awaitUntil("깨우기 스레드의 claim 호출 완료", () -> kickResults().size() >= 1, flight);
            assertThat(kickResults()).as("깨우기 claim은 A=5 ≥ N=1이라 0건").containsOnly(0);
            observeStable(IN_FLIGHT, "하향 직후(깨우기 후)");
            assertClaimNothing(pool, "하향 직후 명시 claim");
            assertExtrasUntouched(IN_FLIGHT, "하향 직후 명시 claim 뒤");

            // 잠금을 티켓 번호 순서대로 하나씩 푼다. 각 진행 중 작업은 SUCCEEDED/OK로 끝나고 A는 5→4→…→0이다
            for (int k = 0; k < IN_FLIGHT; k++) {
                holders.get(k).commit();
                long finished = inFlight.get(k);
                awaitUntil("티켓 " + k + " 종결", () -> "SUCCEEDED".equals(status(finished)), flight);

                Map<String, Object> done = row(finished);
                assertThat(done.get("result_cd")).isEqualTo("OK");
                assertThat(done.get("retry_count")).as("재투입 없음").isEqualTo(0);
                assertThat(((Number) done.get("generation_no")).longValue()).as("세대 불변: 중단·회수 없음").isEqualTo(generation[k]);
                assertThat(done.get("processing_finished_at")).isNotNull();
                int remaining = IN_FLIGHT - 1 - k;
                assertThat(processingCount()).as("A는 하나씩 줄어든다").isEqualTo(remaining);
                for (int j = k + 1; j < IN_FLIGHT; j++) {
                    assertThat(status(inFlight.get(j))).as("아직 끝나지 않은 진행 중 작업은 PROCESSING 그대로 (WAITING 복귀 없음)")
                        .isEqualTo("PROCESSING");
                }
                assertThat(sql.queryForObject("SELECT count(*) FROM enrollments WHERE course_inst_id=CAST(? AS uuid)",
                    Integer.class, courses.get(k))).as("신청 내역이 실제로 남는다").isEqualTo(1);

                if (remaining > 0) {
                    // 다음 핸들러가 시작해 자기 과목 잠금에서 기다린다
                    awaitBlockedBy(observer, holderPids.get(k + 1), "다음 핸들러가 과목 잠금 대기", flight);
                    assertClaimNothing(pool, "A=" + remaining + " 명시 claim");
                    assertExtrasUntouched(remaining, "A=" + remaining);
                }
            }

            // 5건 처리가 모두 끝나 claim 호출이 5를 돌려준다
            assertThat(flight.get(WAIT_SECONDS, TimeUnit.SECONDS)).isEqualTo(IN_FLIGHT);
            assertThat(processingCount()).as("A=0").isZero();
            assertExtrasUntouched(0, "A=0 직후 claim 전");

            // A=0이 되어야 N′=1이다: 가장 작은 WAITING 번호 하나만 claim하고 처리한다
            Future<Integer> next = pool.submit(dispatcher::claimAndDispatch);
            assertThat(next.get(WAIT_SECONDS, TimeUnit.SECONDS)).as("A=0 이후 정확히 1건").isEqualTo(1);
            Map<String, Object> first = row(ticketIds.get(IN_FLIGHT));
            assertThat(first.get("status_cd")).as("가장 작은 WAITING 번호가 claim돼 끝난다").isEqualTo("SUCCEEDED");
            assertThat(first.get("result_cd")).isEqualTo("OK");
            assertThat(first.get("generation_no")).isEqualTo(1);
            for (int i = IN_FLIGHT + 1; i < IN_FLIGHT + EXTRA; i++) {
                assertThat(row(ticketIds.get(i)).get("status_cd")).as("나머지는 WAITING 그대로").isEqualTo("WAITING");
                assertThat(row(ticketIds.get(i)).get("generation_no")).isEqualTo(0);
            }
            assertThat(sql.queryForObject("SELECT count(*) FROM tickets WHERE request_type='ENROLLMENT' AND status_cd='SUCCEEDED'", Integer.class))
                .isEqualTo(IN_FLIGHT + 1);
        } finally {
            // 잠금을 먼저 놓아야 막힌 스레드가 끝난다. 정리 중 예외가 시험 실패 원인을 덮지 않게 연결 종료 오류는 무시한다
            for (Connection holder : holders) {
                try {
                    if (!holder.isClosed()) {
                        holder.rollback();
                    }
                } catch (Exception ignored) {
                    // 이미 커밋했거나 닫힌 연결
                }
                try {
                    holder.close();
                } catch (Exception ignored) {
                    // 정리 실패는 무시
                }
            }
            pool.shutdownNow();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }
}
