package com.crs.backend.concurrency;

import com.crs.backend.TestFixtures;
import com.crs.backend.application.TicketDispatcher;
import com.crs.backend.queue.QueueAdmissionScheduler;
import com.crs.backend.queue.QueueProperties;
import java.sql.Connection;
import java.time.Duration;
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
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * BE-17 티켓별 병렬 디스패치 통합 테스트(구현계획 4절 (6)). 실제 PostgreSQL 위에서 돈다.
 * claimAndDispatch()는 claim 커밋 뒤 티켓마다 작업을 실행기에 넘기고 곧바로 claim 수를 돌려준다.
 * 핸들러의 과목 행 잠금(FOR NO KEY UPDATE)을 별도 연결이 쥐어 핸들러를 임의로 붙잡아 두고 동작을 관찰한다.
 * 동시 처리 상한은 이 클래스 한정으로 2로 낮춘다(queue.worker-concurrency).
 * 백그라운드 스케줄은 mock으로 꺼 두므로 claim은 테스트가 직접 호출할 때만 일어난다.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(properties = {
    "queue.scheduler-fixed-delay-ms=3600000",
    "queue.worker-concurrency=2",
    "spring.datasource.hikari.maximum-pool-size=20"
})
@Import(ParallelDispatchIntegrationTest.Database.class)
class ParallelDispatchIntegrationTest {
    @Container
    static final GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("queue.waiting-key", () -> "parallel-dispatch-test:waiting");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Database {
        @Bean @ServiceConnection
        PostgreSQLContainer postgres() {
            return new PostgreSQLContainer(DockerImageName.parse("postgres:17"));
        }
    }

    private static final String CODE_X = "PARX0001";
    private static final String CODE_Y = "PARY0001";
    private static final String CODE_Z = "PARZ0001";
    private static final long WAIT_SECONDS = 10;
    /** claimAndDispatch()가 핸들러를 기다리지 않고 돌아와야 하는 한도. 핸들러는 잠금에 묶여 있으므로 이보다 오래 걸리면 기다린 것이다 */
    private static final long RETURN_PROMPTLY_SECONDS = 5;

    @Autowired JdbcTemplate sql;
    @Autowired JdbcClient jdbc;
    @Autowired DataSource dataSource;
    @Autowired TicketDispatcher dispatcher;
    @Autowired RedissonClient redisson;
    @Autowired QueueProperties queueProperties;
    @Autowired Environment environment;
    /** 주기 스케줄러의 claim이 테스트 몰래 티켓을 가져가지 않게 끈다 */
    @MockitoBean QueueAdmissionScheduler scheduler;

    private String courseX;
    private String courseY;
    private String courseZ;
    private int userSeq;
    private final List<ExecutorService> pools = new ArrayList<>();
    private final List<Connection> holders = new ArrayList<>();

    @BeforeEach void setup() {
        sql.execute("TRUNCATE enrollments, refresh_tokens, sessions, tickets, courses, users CASCADE");
        sql.update("INSERT INTO users VALUES ('prof', 'hash', 'Professor', 'PROFESSOR')");
        sql.update("""
            INSERT INTO service_settings(singleton, concurrency_limit, last_changed_by, last_changed_at)
            VALUES (true, 100, NULL, NULL)
            ON CONFLICT (singleton) DO UPDATE SET concurrency_limit=100,
                last_changed_by=NULL, last_changed_at=NULL
            """);
        courseX = course(CODE_X);
        courseY = course(CODE_Y);
        courseZ = course(CODE_Z);
        userSeq = 0;
        redisson.getScoredSortedSet(queueProperties.getWaitingKey()).delete();
    }

    /** 잠금을 모두 풀고 남은 작업이 끝나기를 기다린다. 테스트 본문의 실패를 가리지 않도록 정리 중 예외는 삼킨다 */
    @AfterEach void cleanup() throws InterruptedException {
        releaseAll();
        boolean idle = dispatcher.awaitIdle(Duration.ofSeconds(30));
        for (ExecutorService pool : pools) pool.shutdownNow();
        for (ExecutorService pool : pools) {
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).as("스레드 정리").isTrue();
        }
        pools.clear();
        redisson.getScoredSortedSet(queueProperties.getWaitingKey()).delete();
        assertThat(idle).as("다음 테스트로 넘어가기 전에 디스패처가 한가해야 한다").isTrue();
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

    /** 접수 직후 WAITING인 신청 티켓(학생 1명, 입장 처리된 세션). 생성 순서가 곧 티켓 번호 순서다 */
    private long waiting(String courseInstId, String code) {
        String user = "par" + (userSeq++);
        sql.update("INSERT INTO users VALUES (?, 'hash', ?, 'STUDENT')", user, user);
        String session = TestFixtures.session(jdbc, user, "STUDENT");
        sql.update("UPDATE sessions SET entered_at=clock_timestamp() WHERE session_id=CAST(? AS uuid)", session);
        return TestFixtures.waitingEnrollmentTicket(jdbc, user, session, courseInstId, code);
    }

    private Map<String, Object> row(long ticketId) {
        return sql.queryForMap("""
            SELECT status_cd, result_cd, retry_count, generation_no, owner_worker_ref, lease_info,
                   slot_claim_ref, processing_finished_at
            FROM tickets WHERE ticket_id=?""", ticketId);
    }

    private String status(long ticketId) {
        return sql.queryForObject("SELECT status_cd FROM tickets WHERE ticket_id=?", String.class, ticketId);
    }

    private boolean succeededOk(long ticketId) {
        Map<String, Object> row = row(ticketId);
        return "SUCCEEDED".equals(row.get("status_cd")) && "OK".equals(row.get("result_cd"));
    }

    private int processingCount() {
        return sql.queryForObject("SELECT count(*) FROM tickets WHERE status_cd='PROCESSING'", Integer.class);
    }

    private boolean finishedBefore(long first, long second) {
        return sql.queryForObject("""
            SELECT (SELECT processing_finished_at FROM tickets WHERE ticket_id=?)
                 < (SELECT processing_finished_at FROM tickets WHERE ticket_id=?)""", Boolean.class, first, second);
    }

    /** 새 연결에서 과목 행을 FOR NO KEY UPDATE로 잡고 그 연결의 백엔드 pid를 돌려준다. 해제는 release()로 한다 */
    private Holder lockCourse(String courseInstId) throws Exception {
        Connection connection = dataSource.getConnection();
        holders.add(connection);
        connection.setAutoCommit(false);
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT 1 FROM courses WHERE course_inst_id=CAST('"
                 + courseInstId + "' AS uuid) FOR NO KEY UPDATE")) {
            assertThat(result.next()).as("과목 행 잠금 획득").isTrue();
        }
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT pg_backend_pid()")) {
            result.next();
            return new Holder(connection, result.getInt(1));
        }
    }

    private record Holder(Connection connection, int pid) {}

    private void release(Holder holder) throws Exception {
        try {
            holder.connection().rollback();
        } finally {
            holders.remove(holder.connection());
            holder.connection().close();
        }
    }

    private void releaseAll() {
        for (Connection connection : new ArrayList<>(holders)) {
            try {
                connection.rollback();
            } catch (Exception ignored) {
                // 정리 중 예외는 본문의 실패를 가리지 않는다
            }
            try {
                connection.close();
            } catch (Exception ignored) {
                // 위와 같다
            }
        }
        holders.clear();
    }

    /** 해당 pid가 쥔 잠금을 기다리는 세션 수 */
    private int blockedBy(Holder holder) {
        return sql.queryForObject(
            "SELECT count(*) FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))", Integer.class, holder.pid());
    }

    private int blockedBy(List<Holder> holderList) {
        int sum = 0;
        for (Holder holder : holderList) sum += blockedBy(holder);
        return sum;
    }

    private void await(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).as("제한 시간 안에 " + what).isLessThan(deadline);
            Thread.sleep(50);
        }
    }

    /** claimAndDispatch()를 별도 스레드에서 부르되, 핸들러를 기다리지 않고 짧은 한도 안에 돌아오는지 함께 확인한다 */
    private int claimPromptly(ExecutorService pool) throws Exception {
        Future<Integer> future = pool.submit((Callable<Integer>) dispatcher::claimAndDispatch);
        long start = System.nanoTime();
        int claimed = future.get(RETURN_PROMPTLY_SECONDS, TimeUnit.SECONDS);
        assertThat(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start))
            .as("핸들러가 잠금에 묶여 있어도 claimAndDispatch()는 기다리지 않고 돌아온다")
            .isLessThan(RETURN_PROMPTLY_SECONDS);
        return claimed;
    }

    @Test void 설정이_이_클래스의_동시_처리_상한을_2로_낮춘다() {
        assertThat(environment.getProperty("queue.worker-concurrency")).isEqualTo("2");
    }

    @Test void 과목_X_잠금에_묶인_티켓_A가_있어도_다른_과목의_티켓_B는_먼저_끝나고_해제하면_A도_끝난다() throws Exception {
        long a = waiting(courseX, CODE_X);
        long b = waiting(courseY, CODE_Y);
        ExecutorService pool = pool();
        Holder holderX = lockCourse(courseX);

        try {
            assertThat(claimPromptly(pool)).as("한 배치로 A·B 모두 claim").isEqualTo(2);

            // B는 X 잠금과 무관하므로 A가 묶여 있는 동안에도 끝난다
            await("B 종결", () -> succeededOk(b));
            await("A가 과목 X 잠금을 기다림", () -> blockedBy(holderX) >= 1);
            assertThat(status(a)).as("A는 아직 PROCESSING").isEqualTo("PROCESSING");
            assertThat(row(a).get("result_cd")).isNull();
            assertThat(row(b).get("retry_count")).isEqualTo(0);
            assertThat(processingCount()).isEqualTo(1);
            assertThat(dispatcher.awaitIdle(Duration.ofMillis(200))).as("A가 실행 중이므로 한가하지 않다").isFalse();
        } finally {
            release(holderX);
        }

        await("A 종결", () -> succeededOk(a));
        assertThat(dispatcher.awaitIdle(Duration.ofSeconds(WAIT_SECONDS))).as("모두 끝나면 한가하다").isTrue();
        assertThat(row(a).get("owner_worker_ref")).isNull();
        assertThat(row(a).get("slot_claim_ref")).isNull();
        assertThat(row(a).get("lease_info")).isNull();
        assertThat(processingCount()).isZero();
        assertThat(sql.queryForObject("SELECT count(*) FROM enrollments WHERE enroll_status='ENROLLED'", Integer.class))
            .isEqualTo(2);
    }

    @Test void 동시_처리_상한이_2이면_과목_3개가_잠겨_있어도_잠금을_기다리는_핸들러는_동시에_2개를_넘지_않는다() throws Exception {
        long t1 = waiting(courseX, CODE_X);
        long t2 = waiting(courseY, CODE_Y);
        long t3 = waiting(courseZ, CODE_Z);
        ExecutorService pool = pool();
        Holder holderX = lockCourse(courseX);
        Holder holderY = lockCourse(courseY);
        Holder holderZ = lockCourse(courseZ);
        List<Holder> all = List.of(holderX, holderY, holderZ);

        try {
            assertThat(claimPromptly(pool)).as("한 배치로 3건 claim").isEqualTo(3);

            await("핸들러 2개가 과목 잠금을 기다림", () -> blockedBy(all) >= 2);
            // 3번째 핸들러는 허가를 기다리므로 일정 시간 지켜봐도 잠금 대기가 2개를 넘지 않는다. 그동안 세 티켓은 모두 PROCESSING이다
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (System.nanoTime() < until) {
                assertThat(blockedBy(all)).as("잠금 대기 핸들러 수는 상한 2 이하").isLessThanOrEqualTo(2);
                assertThat(processingCount()).as("세 티켓 모두 PROCESSING(허가 대기 중인 것 포함)").isEqualTo(3);
                Thread.sleep(50);
            }
            assertThat(blockedBy(all)).isEqualTo(2);
            assertThat(dispatcher.awaitIdle(Duration.ofMillis(200))).isFalse();

            // 묶인 핸들러가 있는 과목 하나를 풀면 그 핸들러가 끝나 허가가 반환되고, 허가를 기다리던 세 번째 핸들러가 시작해 다른 잠금에 묶인다
            Holder released = all.stream().filter(h -> blockedBy(h) > 0).findFirst().orElseThrow();
            List<Holder> remaining = new ArrayList<>(all);
            remaining.remove(released);
            release(released);
            await("허가가 반환되어 세 번째 핸들러가 남은 잠금에서 대기", () -> blockedBy(remaining) == 2);
            assertThat(blockedBy(remaining)).as("여전히 상한 2").isEqualTo(2);
            assertThat(processingCount()).as("하나만 끝남").isEqualTo(2);
        } finally {
            releaseAll();
        }

        await("세 티켓 모두 종결", () -> succeededOk(t1) && succeededOk(t2) && succeededOk(t3));
        assertThat(dispatcher.awaitIdle(Duration.ofSeconds(WAIT_SECONDS))).isTrue();
        assertThat(processingCount()).isZero();
        assertThat(sql.queryForObject("SELECT count(*) FROM enrollments WHERE enroll_status='ENROLLED'", Integer.class))
            .isEqualTo(3);
    }

    @Test void awaitIdle은_핸들러가_묶여_있으면_제한_시간_안에_false를_돌려주고_해제_뒤에는_true를_돌려준다() throws Exception {
        assertThat(dispatcher.awaitIdle(Duration.ofSeconds(1))).as("제출된 작업이 없으면 바로 true").isTrue();

        long a = waiting(courseX, CODE_X);
        ExecutorService pool = pool();
        Holder holderX = lockCourse(courseX);

        try {
            assertThat(claimPromptly(pool)).isEqualTo(1);
            await("A가 과목 X 잠금을 기다림", () -> blockedBy(holderX) >= 1);

            long start = System.nanoTime();
            boolean idle = dispatcher.awaitIdle(Duration.ofMillis(500));
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertThat(idle).as("묶인 핸들러가 있으면 timeout에 false(예외 없음)").isFalse();
            assertThat(elapsedMs).as("timeout 근처까지 기다린다").isBetween(400L, 5_000L);
            assertThat(status(a)).isEqualTo("PROCESSING");
        } finally {
            release(holderX);
        }

        assertThat(dispatcher.awaitIdle(Duration.ofSeconds(WAIT_SECONDS))).as("해제 뒤에는 true").isTrue();
        assertThat(succeededOk(a)).as("true를 받은 시점에는 핸들러가 끝나 있다").isTrue();
    }

    @Test void 같은_과목의_1번_2번을_한_배치로_claim하면_한_작업_안에서_순서대로_처리되어_복귀없이_1번이_먼저_끝난다() throws Exception {
        long t1 = waiting(courseX, CODE_X);
        long t2 = waiting(courseX, CODE_X);
        ExecutorService pool = pool();
        Holder holderX = lockCourse(courseX);

        try {
            assertThat(claimPromptly(pool)).as("한 배치로 1번·2번 claim").isEqualTo(2);

            // 같은 배치·같은 과목은 한 작업이 번호 순으로 처리한다. 1번이 잠금에 묶이면 2번은 그 뒤에서 기다린다
            await("1번이 과목 X 잠금을 기다림", () -> blockedBy(holderX) >= 1);
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (System.nanoTime() < until) {
                assertThat(status(t2)).as("2번은 WAITING으로 돌아가지 않고 PROCESSING으로 사슬 뒤에서 기다린다")
                    .isEqualTo("PROCESSING");
                assertThat(blockedBy(holderX)).as("잠금에 묶인 세션은 1개뿐").isEqualTo(1);
                Thread.sleep(50);
            }
            Map<String, Object> second = row(t2);
            assertThat(second.get("generation_no")).isEqualTo(1);
            assertThat(second.get("retry_count")).isEqualTo(0);
            assertThat(second.get("result_cd")).isNull();
            assertThat(status(t1)).isEqualTo("PROCESSING");
            assertThat(processingCount()).isEqualTo(2);
        } finally {
            release(holderX);
        }

        await("1번·2번 종결", () -> succeededOk(t1) && succeededOk(t2));
        assertThat(dispatcher.awaitIdle(Duration.ofSeconds(WAIT_SECONDS))).isTrue();
        assertThat(row(t1).get("generation_no")).isEqualTo(1);
        assertThat(row(t2).get("generation_no")).as("다시 claim되지 않았다").isEqualTo(1);
        assertThat(row(t2).get("retry_count")).isEqualTo(0);
        assertThat(finishedBefore(t1, t2)).as("1번의 종결 시각 < 2번의 종결 시각").isTrue();
        assertThat(sql.queryForObject("SELECT count(*) FROM enrollments WHERE enroll_status='ENROLLED'", Integer.class))
            .isEqualTo(2);
        assertThat(processingCount()).isZero();
    }

    @Test void 같은_과목의_2번이_다음_배치로_claim되면_1번이_비행_중인_동안_횟수_증가없이_복귀하고_1번이_먼저_끝난다() throws Exception {
        long t1 = waiting(courseX, CODE_X);
        ExecutorService pool = pool();
        Holder holderX = lockCourse(courseX);
        long t2;

        try {
            assertThat(claimPromptly(pool)).as("1번만 claim").isEqualTo(1);
            await("1번이 과목 X 잠금을 기다림", () -> blockedBy(holderX) >= 1);

            t2 = waiting(courseX, CODE_X);
            assertThat(claimPromptly(pool)).as("두 번째 배치로 2번 claim").isEqualTo(1);

            // 다른 배치라 사슬에 묶이지 않는다. 앞 번호 1번이 미완료이므로 2번은 잠금에 닿지 않고 WAITING으로 돌아온다
            long second = t2;
            await("2번이 WAITING으로 복귀", () -> "WAITING".equals(status(second)));
            Map<String, Object> returned = row(t2);
            assertThat(returned.get("generation_no")).as("claim 때의 세대 그대로").isEqualTo(1);
            assertThat(returned.get("retry_count")).isEqualTo(0);
            assertThat(returned.get("owner_worker_ref")).isNull();
            assertThat(returned.get("lease_info")).isNull();
            assertThat(returned.get("slot_claim_ref")).isNull();
            assertThat(returned.get("result_cd")).isNull();
            assertThat(status(t1)).as("1번은 비행 중").isEqualTo("PROCESSING");
            assertThat(processingCount()).isEqualTo(1);
        } finally {
            release(holderX);
        }

        await("1번 종결", () -> succeededOk(t1));
        assertThat(dispatcher.awaitIdle(Duration.ofSeconds(WAIT_SECONDS))).isTrue();
        assertThat(status(t2)).as("이후 claim 전까지 2번은 WAITING").isEqualTo("WAITING");

        assertThat(claimPromptly(pool)).as("2번만 다시 claim").isEqualTo(1);
        await("2번 종결", () -> succeededOk(t2));
        assertThat(dispatcher.awaitIdle(Duration.ofSeconds(WAIT_SECONDS))).isTrue();

        Map<String, Object> done = row(t2);
        assertThat(done.get("generation_no")).as("재배정으로 세대 +1").isEqualTo(2);
        assertThat(done.get("retry_count")).isEqualTo(0);
        assertThat(row(t1).get("generation_no")).isEqualTo(1);
        assertThat(finishedBefore(t1, t2)).as("1번의 종결 시각 < 2번의 종결 시각").isTrue();
        assertThat(sql.queryForObject("SELECT count(*) FROM enrollments WHERE enroll_status='ENROLLED'", Integer.class))
            .isEqualTo(2);
        assertThat(processingCount()).isZero();
    }
}
