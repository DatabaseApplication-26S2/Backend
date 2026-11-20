package com.crs.backend.concurrency;

import com.crs.backend.TestFixtures;
import com.crs.backend.adapter.out.persistence.jpa.WaitingTicketJpaRepository;
import com.crs.backend.application.TicketLeaseRenewer;
import com.crs.backend.application.TicketReclaimer;
import com.crs.backend.domain.entity.WaitingTicket;
import com.crs.backend.queue.QueueProperties;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import com.crs.backend.application.TicketDispatcher;
import com.crs.backend.application.handler.EnrollmentTicketHandler;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

/**
 * BE-17 티켓 회수·리스 연장 통합 테스트(구현계획 4절 (6)~(9), 7절 T8). 실제 PostgreSQL 위에서 돈다.
 * 백그라운드 스케줄은 src/test/resources/config/application.properties가 꺼 두므로 메서드를 직접 호출한다.
 * 티켓은 CHECK 3을 지키는 SQL로 PROCESSING 상태를 만들고, 리스 시각은 DB 시계(clock_timestamp())로 조작한다.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(properties = {
    "queue.scheduler-fixed-delay-ms=3600000",
    "spring.datasource.hikari.maximum-pool-size=20"
})
@Import(TicketRecoveryIntegrationTest.Database.class)
class TicketRecoveryIntegrationTest {
    @Container
    static final GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("queue.waiting-key", () -> "recovery-test:waiting");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Database {
        @Bean @ServiceConnection
        PostgreSQLContainer postgres() {
            return new PostgreSQLContainer(DockerImageName.parse("postgres:17"));
        }
    }

    private static final String CODE = "RECOV001";
    private static final String OTHER_WORKER = "worker-old";

    @Autowired JdbcTemplate sql;
    @Autowired JdbcClient jdbc;
    @Autowired DataSource dataSource;
    @Autowired TicketReclaimer reclaimer;
    @Autowired TicketLeaseRenewer renewer;
    @Autowired WaitingTicketJpaRepository tickets;
    @Autowired TransactionTemplate tx;
    @Autowired RedissonClient redisson;
    @Autowired QueueProperties queueProperties;
    @Autowired TicketDispatcher dispatcher;
    /** 같은 빈에 스파이를 씌운다. 테스트가 끝나면 스텁과 호출 기록은 자동으로 초기화된다 */
    @MockitoSpyBean EnrollmentTicketHandler enrollmentHandler;

    private String course;
    private int userSeq;
    private final List<long[]> tracked = new ArrayList<>();

    @BeforeEach void setup() {
        sql.execute("TRUNCATE enrollments, refresh_tokens, sessions, tickets, courses, users CASCADE");
        sql.update("""
            INSERT INTO service_settings(singleton, concurrency_limit, last_changed_by, last_changed_at)
            VALUES (true, 100, NULL, NULL)
            ON CONFLICT (singleton) DO UPDATE SET concurrency_limit=100,
                last_changed_by=NULL, last_changed_at=NULL
            """);
        sql.update("INSERT INTO users VALUES ('prof', 'hash', 'Professor', 'PROFESSOR')");
        course = UUID.randomUUID().toString();
        sql.update("""
            INSERT INTO courses(course_inst_id,course_code,professor_id,course_title,course_category,
              grading_type,max_capacity,min_enrollment,classroom,course_created_at)
            VALUES (CAST(? AS uuid),?,'prof','Course','MAJOR','ABSOLUTE',3,1,'Room',clock_timestamp())
            """, course, CODE);
        userSeq = 0;
        tracked.clear();
        redisson.getScoredSortedSet(queueProperties.getWaitingKey()).delete();
    }

    @AfterEach void cleanup() {
        // 리뉴어의 추적 집합은 컨텍스트 전체가 공유하므로 이 테스트가 넣은 항목을 모두 뺀다
        for (long[] entry : tracked) renewer.untrack(entry[0], (int) entry[1]);
        tracked.clear();
        redisson.getScoredSortedSet(queueProperties.getWaitingKey()).delete();
    }

    /** 사용자 1명(사용자당 미완료 티켓 1건, INV-5)과 PROCESSING 티켓 1건을 만든다. leaseOffset은 지금 기준 PostgreSQL interval 문자열 */
    private long processing(int generation, int retryCount, String owner, String leaseOffset) {
        String user = "recov" + (userSeq++);
        sql.update("INSERT INTO users VALUES (?, 'hash', ?, 'STUDENT')", user, user);
        String session = TestFixtures.session(jdbc, user, "STUDENT");
        return jdbc.sql("""
                INSERT INTO tickets (request_type, requester_user_id, session_ref, target_course_inst_id,
                                     course_code_snapshot, ticket_issued_at, status_cd, processing_started_at,
                                     retry_count, owner_worker_ref, generation_no, lease_info, slot_claim_ref)
                VALUES ('ENROLLMENT', :user, CAST(:session AS uuid), CAST(:course AS uuid), :code,
                        clock_timestamp() - interval '1 minute', 'PROCESSING', clock_timestamp() - interval '40 seconds',
                        :retry, :owner, :generation, clock_timestamp() + CAST(:lease AS interval), :generation)
                RETURNING ticket_id""")
            .param("user", user).param("session", session).param("course", course).param("code", CODE)
            .param("retry", retryCount).param("owner", owner).param("generation", generation)
            .param("lease", leaseOffset)
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

    private void track(long ticketId, int generation) {
        renewer.track(ticketId, generation);
        tracked.add(new long[] {ticketId, generation});
    }

    /** 락을 쥔 다른 연결이 열려 있는 동안 호출이 막히지 않고 제한 시간 안에 끝나는지 확인한다 */
    private int withinTimeout(Callable<Integer> call) throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> future = pool.submit(call);
            return future.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void lockRow(Connection connection, long ticketId) throws Exception {
        connection.setAutoCommit(false);
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT 1 FROM tickets WHERE ticket_id=" + ticketId + " FOR UPDATE")) {
            assertThat(result.next()).as("행 잠금 획득").isTrue();
        }
    }

    /** 접수 직후 대기 중(WAITING)인 학생 신청 티켓 1건과 그 사용자·세션을 만든다. 디스패처가 실제로 배정하는 대상이다 */
    private long waiting() {
        String user = "recov" + (userSeq++);
        sql.update("INSERT INTO users VALUES (?, 'hash', ?, 'STUDENT')", user, user);
        String session = TestFixtures.session(jdbc, user, "STUDENT");
        sql.update("UPDATE sessions SET entered_at=clock_timestamp() WHERE session_id=CAST(? AS uuid)", session);
        return TestFixtures.waitingEnrollmentTicket(jdbc, user, session, course, CODE);
    }

    private int activeCnt() {
        return sql.queryForObject("SELECT active_cnt FROM courses WHERE course_inst_id=CAST(? AS uuid)", Integer.class, course);
    }

    private boolean leaseBeyond(long ticketId, int seconds) {
        return sql.queryForObject("SELECT lease_info > clock_timestamp() + (? * interval '1 second') FROM tickets WHERE ticket_id=?",
            Boolean.class, seconds, ticketId);
    }

    @Test void t8_리스가_만료된_처리중_티켓은_같은_번호로_대기에_복귀하고_횟수와_세대가_오른다() {
        long id = processing(1, 0, WaitingTicket.WORKER_REF, "-5 seconds");

        assertThat(reclaimer.reclaimExpired()).isEqualTo(1);

        Map<String, Object> after = row(id);
        assertThat(after.get("status_cd")).isEqualTo("WAITING");
        assertThat(after.get("retry_count")).isEqualTo(1);
        assertThat(after.get("generation_no")).isEqualTo(2);
        assertThat(after.get("owner_worker_ref")).isNull();
        assertThat(after.get("lease_info")).isNull();
        assertThat(after.get("slot_claim_ref")).isNull();
        assertThat(after.get("processing_started_at")).isNull();
        assertThat(after.get("result_cd")).isNull();
        assertThat(after.get("processing_finished_at")).isNull();
        assertThat(sql.queryForObject("SELECT count(*) FROM tickets WHERE request_type='ENROLLMENT'", Integer.class))
            .as("새 티켓을 만들지 않고 같은 번호로 복귀").isEqualTo(1);
        assertThat(redisson.getScoredSortedSet(queueProperties.getWaitingKey()).contains(String.valueOf(id)))
            .as("커밋 뒤 Redis 대기 순서 사본에 다시 올라간다").isTrue();
    }

    @Test void t8_네번째_회수는_재투입_한도를_넘어_E99로_종결한다() {
        long id = processing(4, WaitingTicket.MAX_RETRY, WaitingTicket.WORKER_REF, "-5 seconds");

        assertThat(reclaimer.reclaimExpired()).isEqualTo(1);

        Map<String, Object> after = row(id);
        assertThat(after.get("status_cd")).isEqualTo("REJECTED");
        assertThat(after.get("result_cd")).isEqualTo("E99");
        assertThat(after.get("processing_finished_at")).isNotNull();
        assertThat(after.get("retry_count")).as("한도 도달 뒤에는 횟수를 더하지 않는다").isEqualTo(WaitingTicket.MAX_RETRY);
        assertThat(after.get("generation_no")).isEqualTo(5);
        assertThat(after.get("owner_worker_ref")).isNull();
        assertThat(after.get("lease_info")).isNull();
        assertThat(after.get("slot_claim_ref")).isNull();
        assertThat(redisson.getScoredSortedSet(queueProperties.getWaitingKey()).contains(String.valueOf(id)))
            .as("종결 티켓은 대기 순서에 다시 올리지 않는다").isFalse();
    }

    @Test void t8_회수를_세번_거치면_대기로_돌아오고_네번째에서_E99가_된다() {
        long id = processing(1, 0, WaitingTicket.WORKER_REF, "-5 seconds");
        for (int round = 1; round <= WaitingTicket.MAX_RETRY; round++) {
            assertThat(reclaimer.reclaimExpired()).isEqualTo(1);
            assertThat(row(id).get("status_cd")).isEqualTo("WAITING");
            assertThat(row(id).get("retry_count")).isEqualTo(round);
            // 다시 배정된 상황: 다음 세대로 PROCESSING, 리스는 이미 만료
            assertThat(sql.update("""
                UPDATE tickets SET status_cd='PROCESSING', generation_no=generation_no+1,
                    slot_claim_ref=generation_no+1, owner_worker_ref=?, processing_started_at=clock_timestamp(),
                    lease_info=clock_timestamp() - interval '1 second'
                WHERE ticket_id=? AND status_cd='WAITING'""", WaitingTicket.WORKER_REF, id)).isEqualTo(1);
        }

        assertThat(reclaimer.reclaimExpired()).isEqualTo(1);

        Map<String, Object> after = row(id);
        assertThat(after.get("status_cd")).isEqualTo("REJECTED");
        assertThat(after.get("result_cd")).isEqualTo("E99");
        assertThat(after.get("retry_count")).isEqualTo(WaitingTicket.MAX_RETRY);
    }

    @Test void t8_회수된_뒤_옛_세대의_늦은_핸들러_호출은_업무_데이터를_바꾸지_않고_슬롯은_한_번만_반납된다() {
        long stale = processing(1, 0, WaitingTicket.WORKER_REF, "-5 seconds");
        long other = processing(1, 0, WaitingTicket.WORKER_REF, "30 seconds");
        assertThat(processingCount()).isEqualTo(2);
        // 디스패처가 회수 전에 읽어 둔 스냅샷(세대 1)을 느린 워커가 계속 들고 있는 상황
        WaitingTicket snapshot = tickets.findById(stale).orElseThrow();
        int activeBefore = activeCnt();

        assertThat(reclaimer.reclaimExpired()).isEqualTo(1);
        assertThat(processingCount()).as("회수로 슬롯 1회 반납").isEqualTo(1);
        Map<String, Object> reclaimed = row(stale);

        // 진짜 핸들러로 늦은 종결: 세대가 달라 아무 일도 하지 않는다
        enrollmentHandler.handle(snapshot, 1);

        assertThat(row(stale)).as("티켓 행 변경 없음").isEqualTo(reclaimed);
        assertThat(reclaimed.get("status_cd")).isEqualTo("WAITING");
        assertThat(reclaimed.get("generation_no")).isEqualTo(2);
        assertThat(reclaimed.get("result_cd")).isNull();
        assertThat(sql.queryForObject("SELECT count(*) FROM enrollments", Integer.class)).as("신청 내역 없음").isZero();
        assertThat(activeCnt()).as("active_cnt 그대로").isEqualTo(activeBefore);
        assertThat(processingCount()).as("늦은 종결이 슬롯을 두 번 돌려주지 않는다").isEqualTo(1);
        assertThat(row(other).get("status_cd")).isEqualTo("PROCESSING");

        // 영속 엔티티의 세대 조건부 종결도 거부된다
        Boolean finished = tx.execute(status -> {
            WaitingTicket entity = tickets.findById(stale).orElseThrow();
            boolean ok = entity.succeedIfGeneration(1, Instant.now());
            tickets.saveAndFlush(entity);
            return ok;
        });
        assertThat(finished).isFalse();
        assertThat(row(stale)).isEqualTo(reclaimed);
    }

    @Test void 디스패처는_첫_핸들러_전에_배치_전체를_추적해_뒤쪽_티켓의_리스도_연장한다() {
        long first = waiting();
        long second = waiting();
        AtomicInteger renewedDuringFirst = new AtomicInteger(-1);
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            if (calls.getAndIncrement() == 0) {
                // 첫 핸들러가 도는 중: 두 티켓의 리스를 만료 직전으로 당긴 뒤 연장 작업을 돌린다
                sql.update("UPDATE tickets SET lease_info=clock_timestamp() + interval '2 seconds' WHERE status_cd='PROCESSING'");
                renewedDuringFirst.set(renewer.renewLeases());
            }
            return null;
        }).when(enrollmentHandler).handle(any(), anyInt());

        assertThat(dispatcher.claimAndDispatch()).isEqualTo(2);

        assertThat(calls.get()).isEqualTo(2);
        assertThat(renewedDuringFirst.get()).as("아직 처리 전인 두 번째 티켓까지 추적 중").isEqualTo(2);
        assertThat(leaseBeyond(second, 25)).as("두 번째 티켓의 리스가 연장됨").isTrue();
        assertThat(leaseBeyond(first, 25)).isTrue();
    }

    @Test void 핸들러가_실패하면_PROCESSING으로_남고_추적은_내려간다() {
        long id = waiting();
        doThrow(new IllegalStateException("워커 강제 중단")).when(enrollmentHandler).handle(any(), anyInt());

        assertThat(dispatcher.claimAndDispatch()).isEqualTo(1);

        Map<String, Object> after = row(id);
        assertThat(after.get("status_cd")).isEqualTo("PROCESSING");
        assertThat(after.get("generation_no")).isEqualTo(1);
        assertThat(sql.update("UPDATE tickets SET lease_info=clock_timestamp() + interval '2 seconds' WHERE ticket_id=?", id)).isEqualTo(1);
        assertThat(renewer.renewLeases()).as("실패 뒤에는 추적하는 작업이 없다").isZero();
        assertThat(leaseBeyond(id, 5)).isFalse();
    }

    @Test void t8_워커_실패_뒤_회수되고_다시_디스패치되면_같은_번호로_한_번만_성공한다() {
        long id = waiting();
        doThrow(new IllegalStateException("워커 강제 중단")).doCallRealMethod()
            .when(enrollmentHandler).handle(any(), anyInt());

        assertThat(dispatcher.claimAndDispatch()).isEqualTo(1);
        assertThat(row(id).get("status_cd")).isEqualTo("PROCESSING");
        assertThat(sql.update("UPDATE tickets SET lease_info=clock_timestamp() - interval '5 seconds' WHERE ticket_id=?", id)).isEqualTo(1);

        assertThat(reclaimer.reclaimExpired()).isEqualTo(1);
        Map<String, Object> waiting = row(id);
        assertThat(waiting.get("status_cd")).isEqualTo("WAITING");
        assertThat(waiting.get("retry_count")).isEqualTo(1);
        assertThat(waiting.get("generation_no")).isEqualTo(2);

        assertThat(dispatcher.claimAndDispatch()).isEqualTo(1);

        Map<String, Object> done = row(id);
        assertThat(done.get("status_cd")).isEqualTo("SUCCEEDED");
        assertThat(done.get("result_cd")).isEqualTo("OK");
        assertThat(done.get("retry_count")).isEqualTo(1);
        assertThat(done.get("generation_no")).isEqualTo(3);
        assertThat(done.get("owner_worker_ref")).isNull();
        assertThat(sql.queryForObject("SELECT count(*) FROM tickets WHERE request_type='ENROLLMENT'", Integer.class)).isEqualTo(1);
        assertThat(sql.queryForObject("SELECT count(*) FROM enrollments WHERE source_ticket_id=? AND enroll_status='ENROLLED'",
            Integer.class, id)).as("신청 내역은 정확히 1건").isEqualTo(1);
        assertThat(sql.queryForObject("SELECT count(*) FROM enrollments", Integer.class)).isEqualTo(1);
        assertThat(activeCnt()).as("INV-2: active_cnt = ENROLLED 건수").isEqualTo(1);
    }

    @Test void t8_리스가_남은_처리중_티켓은_회수하지_않는다() {
        long id = processing(1, 0, WaitingTicket.WORKER_REF, "20 seconds");

        assertThat(reclaimer.reclaimExpired()).isZero();

        Map<String, Object> after = row(id);
        assertThat(after.get("status_cd")).isEqualTo("PROCESSING");
        assertThat(after.get("generation_no")).isEqualTo(1);
        assertThat(after.get("retry_count")).isEqualTo(0);
        assertThat(after.get("owner_worker_ref")).isEqualTo(WaitingTicket.WORKER_REF);
        assertThat(processingCount()).isEqualTo(1);
    }

    @Test void 다른_트랜잭션이_FOR_UPDATE로_잠근_만료_티켓은_건너뛰고_기다리지_않는다() throws Exception {
        long locked = processing(1, 0, WaitingTicket.WORKER_REF, "-5 seconds");
        long free = processing(1, 0, WaitingTicket.WORKER_REF, "-5 seconds");

        try (Connection holder = dataSource.getConnection()) {
            lockRow(holder, locked);

            int reclaimed = withinTimeout(reclaimer::reclaimExpired);

            assertThat(reclaimed).as("잠긴 행은 건너뛰고 잠기지 않은 행만 회수").isEqualTo(1);
            assertThat(row(free).get("status_cd")).isEqualTo("WAITING");
            holder.rollback();
        }
        Map<String, Object> untouched = row(locked);
        assertThat(untouched.get("status_cd")).isEqualTo("PROCESSING");
        assertThat(untouched.get("generation_no")).isEqualTo(1);
        assertThat(untouched.get("retry_count")).isEqualTo(0);

        // 잠금이 풀리면 다음 주기에 회수된다
        assertThat(reclaimer.reclaimExpired()).isEqualTo(1);
        assertThat(row(locked).get("status_cd")).isEqualTo("WAITING");
    }

    @Test void 기동_회수는_리스가_남아도_다른_처리기_소유_티켓을_회수하고_자기_소유는_건드리지_않는다() {
        long orphan = processing(1, 0, OTHER_WORKER, "20 seconds");
        long mine = processing(1, 0, WaitingTicket.WORKER_REF, "20 seconds");

        assertThat(reclaimer.reclaimOrphans()).isEqualTo(1);

        Map<String, Object> reclaimed = row(orphan);
        assertThat(reclaimed.get("status_cd")).isEqualTo("WAITING");
        assertThat(reclaimed.get("retry_count")).isEqualTo(1);
        assertThat(reclaimed.get("generation_no")).isEqualTo(2);
        assertThat(reclaimed.get("owner_worker_ref")).isNull();
        assertThat(reclaimed.get("lease_info")).isNull();
        assertThat(reclaimed.get("slot_claim_ref")).isNull();
        assertThat(reclaimed.get("processing_started_at")).isNull();

        Map<String, Object> own = row(mine);
        assertThat(own.get("status_cd")).isEqualTo("PROCESSING");
        assertThat(own.get("generation_no")).isEqualTo(1);
        assertThat(own.get("owner_worker_ref")).isEqualTo(WaitingTicket.WORKER_REF);
        assertThat(own.get("retry_count")).isEqualTo(0);
        assertThat(redisson.getScoredSortedSet(queueProperties.getWaitingKey()).contains(String.valueOf(orphan))).isTrue();
    }

    @Test void 기동_회수도_한도에_닿은_고아_티켓은_E99로_종결한다() {
        long orphan = processing(4, WaitingTicket.MAX_RETRY, OTHER_WORKER, "20 seconds");

        assertThat(reclaimer.reclaimOrphans()).isEqualTo(1);

        Map<String, Object> after = row(orphan);
        assertThat(after.get("status_cd")).isEqualTo("REJECTED");
        assertThat(after.get("result_cd")).isEqualTo("E99");
        assertThat(after.get("processing_finished_at")).isNotNull();
        assertThat(after.get("owner_worker_ref")).isNull();
        assertThat(after.get("lease_info")).isNull();
        assertThat(after.get("slot_claim_ref")).isNull();
    }

    @Test void 추적한_티켓의_임박한_리스는_연장되고_세대가_다른_추적은_연장하지_않는다() {
        long current = processing(2, 0, WaitingTicket.WORKER_REF, "2 seconds");
        long stale = processing(3, 0, WaitingTicket.WORKER_REF, "2 seconds");
        track(current, 2);
        track(stale, 2); // 실제 세대는 3

        assertThat(renewer.renewLeases()).isEqualTo(1);

        assertThat(leaseBeyond(current, 25)).as("리스가 지금 + 30초 근처로 연장").isTrue();
        assertThat(leaseBeyond(stale, 5)).as("세대가 다르면 연장하지 않는다").isFalse();
        assertThat(row(current).get("generation_no")).isEqualTo(2);
        assertThat(row(current).get("status_cd")).isEqualTo("PROCESSING");
    }

    @Test void 소유자가_다르거나_처리중이_아닌_추적은_연장하지_않는다() {
        long foreign = processing(1, 0, OTHER_WORKER, "2 seconds");
        long finished = processing(1, 0, WaitingTicket.WORKER_REF, "2 seconds");
        assertThat(sql.update("""
            UPDATE tickets SET status_cd='SUCCEEDED', result_cd='OK', processing_finished_at=clock_timestamp(),
                owner_worker_ref=NULL, lease_info=NULL, slot_claim_ref=NULL WHERE ticket_id=?""", finished)).isEqualTo(1);
        track(foreign, 1);
        track(finished, 1);

        assertThat(renewer.renewLeases()).isZero();

        assertThat(leaseBeyond(foreign, 5)).isFalse();
        assertThat(row(finished).get("lease_info")).isNull();
    }

    @Test void 다른_트랜잭션이_잠근_추적_티켓은_건너뛰고_기다리지_않는다() throws Exception {
        long locked = processing(1, 0, WaitingTicket.WORKER_REF, "2 seconds");
        long free = processing(1, 0, WaitingTicket.WORKER_REF, "2 seconds");
        track(locked, 1);
        track(free, 1);

        try (Connection holder = dataSource.getConnection()) {
            lockRow(holder, locked);

            int renewed = withinTimeout(renewer::renewLeases);

            assertThat(renewed).as("잠긴 행은 건너뛰고 나머지만 연장").isEqualTo(1);
            assertThat(leaseBeyond(free, 25)).isTrue();
            holder.rollback();
        }
        assertThat(leaseBeyond(locked, 5)).as("건너뛴 행의 리스는 그대로").isFalse();

        // 잠금이 풀린 뒤 다음 주기에는 연장된다
        assertThat(renewer.renewLeases()).isEqualTo(2);
        assertThat(leaseBeyond(locked, 25)).isTrue();
    }

    @Test void untrack_뒤에는_더_이상_리스를_연장하지_않는다() {
        long id = processing(1, 0, WaitingTicket.WORKER_REF, "2 seconds");
        track(id, 1);
        assertThat(renewer.renewLeases()).isEqualTo(1);
        assertThat(leaseBeyond(id, 25)).isTrue();

        assertThat(sql.update("UPDATE tickets SET lease_info=clock_timestamp() + interval '2 seconds' WHERE ticket_id=?", id)).isEqualTo(1);
        renewer.untrack(id, 1);

        assertThat(renewer.renewLeases()).isZero();
        assertThat(leaseBeyond(id, 5)).as("추적을 끊으면 리스가 그대로").isFalse();
    }
}
