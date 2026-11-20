package com.crs.backend.concurrency;

import com.crs.backend.adapter.out.persistence.jpa.WaitingTicketJpaRepository;
import com.crs.backend.application.TicketDispatcher;
import com.crs.backend.application.TicketReclaimer;
import com.crs.backend.application.handler.LoginTicketHandler;
import com.crs.backend.auth.AuthService;
import com.crs.backend.auth.LoginCredentialStore;
import com.crs.backend.domain.entity.WaitingTicket;
import com.crs.backend.queue.QueueProperties;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;

/**
 * BE-17 LOGIN 티켓 회수·복구 통합 테스트(구현계획 4절 (8) 4번·(9), 3단계 필수 테스트).
 * 실제 AuthService.login 접수 → 실제 TicketDispatcher 배정 → 실제 TicketReclaimer 회수 순서로 돈다.
 * "배정은 됐지만 핸들러는 아직 안 돈" 상태는 LoginTicketHandler 스파이가 첫 호출만 건너뛰어 만든다.
 * 자격 증명이 소비되지 않았는지는 LoginCredentialStore.consume으로 직접 확인하거나, 이후 배정이 OK로 끝나는 것으로 확인한다.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(properties = {
    "queue.scheduler-fixed-delay-ms=3600000",
    "spring.datasource.hikari.maximum-pool-size=20"
})
@Import(LoginRecoveryIntegrationTest.Database.class)
class LoginRecoveryIntegrationTest {
    @Container
    static final GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("queue.waiting-key", () -> "login-recovery-test:waiting");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Database {
        @Bean @ServiceConnection
        PostgreSQLContainer postgres() {
            return new PostgreSQLContainer(DockerImageName.parse("postgres:17"));
        }
    }

    private static final String USER = "20240001";
    private static final String PASSWORD = "Demo-password1!";
    private static final String OTHER_WORKER = "worker-old";

    @Autowired JdbcTemplate sql;
    @Autowired AuthService auth;
    @Autowired LoginCredentialStore credentials;
    @Autowired TicketDispatcher dispatcher;
    @Autowired TicketReclaimer reclaimer;
    @Autowired WaitingTicketJpaRepository tickets;
    @Autowired RedissonClient redisson;
    @Autowired QueueProperties queueProperties;
    /** 같은 빈에 스파이를 씌운다. 테스트가 끝나면 스텁과 호출 기록은 자동으로 초기화된다 */
    @MockitoSpyBean LoginTicketHandler loginHandler;
    /** auth와 같은 빈. processLogin이 행 잠금을 잡기 직전에 옛 워커를 멈추는 데 쓴다 */
    @MockitoSpyBean AuthService authSpy;

    /** true이면 다음 handle 호출 한 번을 건너뛰고 (스냅샷, 기대 세대)를 기록한다. 이후 호출은 진짜 핸들러가 처리한다 */
    private final AtomicBoolean skipNext = new AtomicBoolean();
    private final AtomicReference<WaitingTicket> skippedSnapshot = new AtomicReference<>();
    private final AtomicInteger skippedGeneration = new AtomicInteger();

    @BeforeEach void setup() {
        sql.execute("TRUNCATE enrollments, refresh_tokens, sessions, tickets, courses, users CASCADE");
        sql.update("""
            INSERT INTO service_settings(singleton, concurrency_limit, last_changed_by, last_changed_at)
            VALUES (true, 100, NULL, NULL)
            ON CONFLICT (singleton) DO UPDATE SET concurrency_limit=100,
                last_changed_by=NULL, last_changed_at=NULL
            """);
        sql.update("INSERT INTO users VALUES (?, ?, 'Demo', 'STUDENT')", USER, new BCryptPasswordEncoder(10).encode(PASSWORD));
        redisson.getScoredSortedSet(queueProperties.getWaitingKey()).delete();
        skipNext.set(false);
        skippedSnapshot.set(null);
        skippedGeneration.set(0);
        doAnswer(invocation -> {
            if (skipNext.compareAndSet(true, false)) {
                skippedSnapshot.set(invocation.getArgument(0));
                skippedGeneration.set(invocation.getArgument(1));
                return null;
            }
            return invocation.callRealMethod();
        }).when(loginHandler).handle(any(), anyInt());
    }

    @AfterEach void cleanup() {
        redisson.getScoredSortedSet(queueProperties.getWaitingKey()).delete();
    }

    /** 실제 접수 경로로 LOGIN 티켓 1건(WAITING)과 메모리 자격 증명을 만든다 */
    private long intake() {
        return Long.parseLong(auth.login(USER, PASSWORD.toCharArray()).ticketId());
    }

    private String contextOf(long ticketId) {
        return sql.queryForObject("SELECT anon_context_id::text FROM tickets WHERE ticket_id=?", String.class, ticketId);
    }

    private Map<String, Object> row(long ticketId) {
        return sql.queryForMap("""
            SELECT status_cd, result_cd, retry_count, generation_no, owner_worker_ref, lease_info,
                   slot_claim_ref, processing_started_at, processing_finished_at
            FROM tickets WHERE ticket_id=?""", ticketId);
    }

    private int sessionCount(long ticketId) {
        return sql.queryForObject("SELECT count(*) FROM sessions WHERE login_ticket_id=?", Integer.class, ticketId);
    }

    /** 배정은 하되 핸들러 실행은 건너뛰어 PROCESSING 상태의 티켓을 남긴다. 건너뛴 (스냅샷, 세대)는 skipped*에 남는다 */
    private void claimWithoutRunning() {
        skipNext.set(true);
        assertThat(dispatcher.claimAndDispatch()).isEqualTo(1);
        assertThat(skipNext.get()).as("핸들러 첫 호출이 건너뛰어졌다").isFalse();
        assertThat(skippedSnapshot.get()).isNotNull();
    }

    private void expireLease(long ticketId) {
        assertThat(sql.update("UPDATE tickets SET lease_info=clock_timestamp() - interval '5 seconds' "
            + "WHERE ticket_id=? AND status_cd='PROCESSING'", ticketId)).isEqualTo(1);
    }

    private void assertFreed(Map<String, Object> row) {
        assertThat(row.get("owner_worker_ref")).isNull();
        assertThat(row.get("lease_info")).isNull();
        assertThat(row.get("slot_claim_ref")).isNull();
    }

    /** 컨텍스트가 아직 메모리에 있으면 사용자 ID를, 없으면 null을 돌려준다. 호출하면 컨텍스트를 소비한다 */
    private String probeCredential(long ticketId) {
        return credentials.consume(contextOf(ticketId), LoginCredentialStore.Credential::userId, null);
    }

    @Test void 실제_회수로_세대가_올라간_뒤_늦게_도는_옛_워커는_아무것도_바꾸지_않고_자격_증명도_쓰지_않는다() {
        long id = intake();
        claimWithoutRunning();
        assertThat(row(id).get("status_cd")).isEqualTo("PROCESSING");
        assertThat(row(id).get("generation_no")).isEqualTo(1);
        assertThat(skippedGeneration.get()).isEqualTo(1);
        expireLease(id);

        assertThat(reclaimer.reclaimExpired()).isEqualTo(1);
        Map<String, Object> reclaimed = row(id);
        assertThat(reclaimed.get("status_cd")).isEqualTo("WAITING");
        assertThat(reclaimed.get("generation_no")).isEqualTo(2);
        assertThat(reclaimed.get("retry_count")).isEqualTo(1);
        assertFreed(reclaimed);

        // 세대 1을 들고 있던 느린 워커가 회수 뒤에 진짜 핸들러를 부른다
        loginHandler.handle(skippedSnapshot.get(), skippedGeneration.get());

        assertThat(row(id)).as("티켓 행 변경 없음").isEqualTo(reclaimed);
        assertThat(sessionCount(id)).as("세션 없음").isZero();
        assertThat(sql.queryForObject("SELECT count(*) FROM sessions", Integer.class)).isZero();

        // 자격 증명이 소비되지 않았으므로 다음 배정이 정상 로그인으로 끝낸다
        assertThat(dispatcher.claimAndDispatch()).isEqualTo(1);

        Map<String, Object> done = row(id);
        assertThat(done.get("status_cd")).isEqualTo("SUCCEEDED");
        assertThat(done.get("result_cd")).isEqualTo("OK");
        assertThat(done.get("generation_no")).isEqualTo(3);
        assertThat(done.get("retry_count")).as("재투입 횟수는 회수 때만 오른다").isEqualTo(1);
        assertThat(done.get("processing_finished_at")).isNotNull();
        assertFreed(done);
        assertThat(sessionCount(id)).as("로그인 티켓당 세션은 정확히 1건").isEqualTo(1);
        assertThat(sql.queryForObject("SELECT user_id FROM sessions WHERE login_ticket_id=?", String.class, id)).isEqualTo(USER);
        assertThat(sql.queryForObject("SELECT count(*) FROM tickets WHERE request_type='LOGIN'", Integer.class))
            .as("같은 번호로 복귀, 새 티켓 없음").isEqualTo(1);

        // 종결 뒤 옛 워커가 또 와도 세션은 늘지 않는다
        loginHandler.handle(skippedSnapshot.get(), skippedGeneration.get());
        assertThat(row(id)).isEqualTo(done);
        assertThat(sessionCount(id)).isEqualTo(1);
    }

    @Test void 새_세대_워커가_PROCESSING으로_티켓을_쥔_동안_옛_세대_워커의_늦은_호출은_행도_자격_증명도_건드리지_않는다() {
        long id = intake();
        claimWithoutRunning();
        WaitingTicket staleSnapshot = skippedSnapshot.get();
        int staleGeneration = skippedGeneration.get();
        assertThat(staleGeneration).isEqualTo(1);
        expireLease(id);
        assertThat(reclaimer.reclaimExpired()).isEqualTo(1);
        assertThat(row(id).get("generation_no")).isEqualTo(2);

        // 새 워커가 세대 3으로 배정받아 PROCESSING을 쥐고 있지만 핸들러는 아직 돌지 않았다
        claimWithoutRunning();
        WaitingTicket currentSnapshot = skippedSnapshot.get();
        int currentGeneration = skippedGeneration.get();
        assertThat(currentGeneration).isEqualTo(3);
        Map<String, Object> held = row(id);
        assertThat(held.get("status_cd")).isEqualTo("PROCESSING");
        assertThat(held.get("generation_no")).isEqualTo(3);
        assertThat(held.get("owner_worker_ref")).isNotNull();
        assertThat(held.get("lease_info")).isNotNull();
        assertThat(held.get("slot_claim_ref")).isEqualTo(3);

        // 세대 1 워커가 이제 와서 돈다: 세대가 달라 아무것도 바꾸지 않는다
        loginHandler.handle(staleSnapshot, staleGeneration);

        assertThat(row(id)).as("PROCESSING 행(세대 3, 소유·리스·슬롯) 변경 없음").isEqualTo(held);
        assertThat(sessionCount(id)).as("세션 없음").isZero();

        // 자격 증명이 소비되지 않았으므로 세대 3 워커가 정상 로그인으로 끝낸다
        loginHandler.handle(currentSnapshot, currentGeneration);

        Map<String, Object> done = row(id);
        assertThat(done.get("status_cd")).isEqualTo("SUCCEEDED");
        assertThat(done.get("result_cd")).isEqualTo("OK");
        assertThat(done.get("generation_no")).isEqualTo(3);
        assertFreed(done);
        assertThat(sessionCount(id)).as("로그인 티켓당 세션은 정확히 1건").isEqualTo(1);
        assertThat(probeCredential(id)).as("세대 3 워커가 자격 증명을 소비했다").isNull();
    }

    @Test void 옛_워커가_세대_확인을_통과한_뒤_잠금_전에_멈춘_사이_회수와_재배정이_되면_잠금_뒤_재확인으로_아무것도_바꾸지_않는다() throws Exception {
        long id = intake();
        claimWithoutRunning();
        WaitingTicket staleSnapshot = skippedSnapshot.get();
        int staleGeneration = skippedGeneration.get();
        assertThat(staleGeneration).isEqualTo(1);

        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean pauseNext = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (pauseNext.compareAndSet(true, false)) {
                reached.countDown();
                // 핸들러의 세대 확인은 통과했고 행 잠금은 아직 잡지 않은 지점
                assertThat(release.await(30, TimeUnit.SECONDS)).as("멈춘 워커가 제한 시간 안에 풀려난다").isTrue();
            }
            return invocation.callRealMethod();
        }).when(authSpy).processLogin(any());

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> stale = pool.submit(() -> loginHandler.handle(staleSnapshot, staleGeneration));
            assertThat(reached.await(10, TimeUnit.SECONDS)).as("옛 워커가 processLogin 진입 직전에 멈췄다").isTrue();
            assertThat(row(id).get("generation_no")).as("멈춘 동안 행 잠금을 쥐지 않아 회수가 막히지 않는다").isEqualTo(1);

            expireLease(id);
            assertThat(reclaimer.reclaimExpired()).isEqualTo(1);
            assertThat(row(id).get("generation_no")).isEqualTo(2);
            claimWithoutRunning();
            WaitingTicket currentSnapshot = skippedSnapshot.get();
            int currentGeneration = skippedGeneration.get();
            assertThat(currentGeneration).isEqualTo(3);
            Map<String, Object> held = row(id);
            assertThat(held.get("status_cd")).isEqualTo("PROCESSING");
            assertThat(held.get("owner_worker_ref")).isNotNull();
            assertThat(held.get("slot_claim_ref")).isEqualTo(3);

            // 옛 워커 재개: 잠금 뒤 상태·세대·소유자 재확인에서 걸러져야 한다
            release.countDown();
            stale.get(30, TimeUnit.SECONDS);

            assertThat(row(id)).as("PROCESSING 행(세대 3) 변경 없음").isEqualTo(held);
            assertThat(sessionCount(id)).as("세션 없음").isZero();

            // 자격 증명이 소비되지 않았으므로 세대 3 워커가 정상 로그인으로 끝낸다
            loginHandler.handle(currentSnapshot, currentGeneration);

            Map<String, Object> done = row(id);
            assertThat(done.get("status_cd")).isEqualTo("SUCCEEDED");
            assertThat(done.get("result_cd")).isEqualTo("OK");
            assertThat(done.get("generation_no")).isEqualTo(3);
            assertFreed(done);
            assertThat(sessionCount(id)).as("로그인 티켓당 세션은 정확히 1건").isEqualTo(1);
            assertThat(probeCredential(id)).as("세대 3 워커가 자격 증명을 소비했다").isNull();
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test void 재시작으로_메모리_컨텍스트가_사라진_대기_LOGIN_티켓은_E01로_종결하고_세션이_없다() {
        long id = intake();
        credentials.discard(contextOf(id));

        assertThat(dispatcher.claimAndDispatch()).isEqualTo(1);

        Map<String, Object> after = row(id);
        assertThat(after.get("status_cd")).isEqualTo("REJECTED");
        assertThat(after.get("result_cd")).isEqualTo("E01");
        assertThat(after.get("processing_finished_at")).isNotNull();
        assertThat(after.get("retry_count")).isEqualTo(0);
        assertFreed(after);
        assertThat(sessionCount(id)).isZero();
        assertThat(sql.queryForObject("SELECT count(*) FROM sessions", Integer.class)).isZero();
    }

    @Test void 리스_만료로_회수된_뒤_컨텍스트가_사라졌으면_다음_배정이_E01로_종결한다() {
        long id = intake();
        claimWithoutRunning();
        expireLease(id);
        assertThat(reclaimer.reclaimExpired()).isEqualTo(1);
        assertThat(row(id).get("status_cd")).isEqualTo("WAITING");
        // 회수 사이에 프로세스가 재시작해 메모리 컨텍스트를 잃은 상황
        credentials.discard(contextOf(id));

        assertThat(dispatcher.claimAndDispatch()).isEqualTo(1);

        Map<String, Object> after = row(id);
        assertThat(after.get("status_cd")).isEqualTo("REJECTED");
        assertThat(after.get("result_cd")).isEqualTo("E01");
        assertThat(after.get("retry_count")).isEqualTo(1);
        assertThat(after.get("generation_no")).isEqualTo(3);
        assertFreed(after);
        assertThat(sessionCount(id)).isZero();
    }

    @Test void 다른_인스턴스_소유의_PROCESSING_LOGIN_티켓은_기동_회수_뒤_컨텍스트가_없으면_E01로_종결한다() {
        long id = intake();
        claimWithoutRunning();
        // 이전 프로세스가 배정해 둔 티켓: 소유자가 현재 인스턴스가 아니고 리스는 아직 남아 있다
        assertThat(sql.update("UPDATE tickets SET owner_worker_ref=? WHERE ticket_id=? AND status_cd='PROCESSING'",
            OTHER_WORKER, id)).isEqualTo(1);
        // 재시작: 이전 프로세스의 메모리 컨텍스트는 사라졌다
        credentials.discard(contextOf(id));

        assertThat(reclaimer.reclaimOrphans()).isEqualTo(1);
        Map<String, Object> reclaimed = row(id);
        assertThat(reclaimed.get("status_cd")).isEqualTo("WAITING");
        assertThat(reclaimed.get("generation_no")).isEqualTo(2);
        assertThat(reclaimed.get("retry_count")).isEqualTo(1);
        assertFreed(reclaimed);

        assertThat(dispatcher.claimAndDispatch()).isEqualTo(1);

        Map<String, Object> after = row(id);
        assertThat(after.get("status_cd")).isEqualTo("REJECTED");
        assertThat(after.get("result_cd")).isEqualTo("E01");
        assertThat(after.get("generation_no")).isEqualTo(3);
        assertFreed(after);
        assertThat(sessionCount(id)).isZero();
        assertThat(sql.queryForObject("SELECT count(*) FROM sessions", Integer.class)).isZero();
    }

    @Test void 재투입을_세번_거친_LOGIN_티켓은_네번째_회수에서_E99로_종결하고_늦은_워커가_자격_증명을_쓰지_않는다() {
        long id = intake();
        for (int round = 1; round <= WaitingTicket.MAX_RETRY; round++) {
            claimWithoutRunning();
            expireLease(id);
            assertThat(reclaimer.reclaimExpired()).isEqualTo(1);
            Map<String, Object> waiting = row(id);
            assertThat(waiting.get("status_cd")).isEqualTo("WAITING");
            assertThat(waiting.get("retry_count")).isEqualTo(round);
            assertThat(waiting.get("generation_no")).isEqualTo(round * 2);
            assertFreed(waiting);
        }

        // 네 번째 배정도 핸들러가 돌기 전에 리스가 끝난다
        claimWithoutRunning();
        assertThat(row(id).get("generation_no")).isEqualTo(7);
        WaitingTicket lateSnapshot = skippedSnapshot.get();
        int lateGeneration = skippedGeneration.get();
        expireLease(id);

        assertThat(reclaimer.reclaimExpired()).isEqualTo(1);

        Map<String, Object> rejected = row(id);
        assertThat(rejected.get("status_cd")).isEqualTo("REJECTED");
        assertThat(rejected.get("result_cd")).isEqualTo("E99");
        assertThat(rejected.get("retry_count")).as("한도 도달 뒤에는 횟수를 더하지 않는다").isEqualTo(WaitingTicket.MAX_RETRY);
        assertThat(rejected.get("generation_no")).isEqualTo(8);
        assertThat(rejected.get("processing_finished_at")).isNotNull();
        assertFreed(rejected);
        assertThat(redisson.getScoredSortedSet(queueProperties.getWaitingKey()).contains(String.valueOf(id)))
            .as("종결 티켓은 대기 순서에 다시 올리지 않는다").isFalse();

        // 세대 7을 들고 있던 늦은 워커: 아무것도 바꾸지 않는다
        loginHandler.handle(lateSnapshot, lateGeneration);

        assertThat(row(id)).as("E99 종결 행 변경 없음").isEqualTo(rejected);
        assertThat(sessionCount(id)).isZero();
        assertThat(sql.queryForObject("SELECT count(*) FROM sessions", Integer.class)).isZero();
        assertThat(probeCredential(id)).as("늦은 워커가 자격 증명을 소비하지 않았다").isEqualTo(USER);
        // 종결된 티켓은 더 배정되지 않는다
        assertThat(dispatcher.claimAndDispatch()).isZero();
        assertThat(row(id)).isEqualTo(rejected);
    }
}
