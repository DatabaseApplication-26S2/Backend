package com.crs.backend.concurrency;

import com.crs.backend.TestFixtures;
import com.crs.backend.application.TicketDispatcher;
import com.crs.backend.auth.AuthService;
import java.util.Map;
import java.util.UUID;
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

/**
 * 실행 시점 세션 재인가 통합 테스트(plan/intake-lock.md C3). 실제 PostgreSQL 위에서 돈다.
 * 티켓은 접수 이후 상태(WAITING)를 SQL로 직접 만들고, 세션·역할·과목 상태를 조작한 뒤
 * TicketDispatcher.claimAndDispatch()로 워커를 한 번 돌려 종결 결과와 내역·active_cnt를 DB에서 확인한다.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(properties = {
    "queue.scheduler-fixed-delay-ms=3600000",
    "spring.datasource.hikari.maximum-pool-size=20"
})
@Import(ExecutionReauthIntegrationTest.Database.class)
class ExecutionReauthIntegrationTest {
    @Container
    static final GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("queue.waiting-key", () -> "reauth-test:waiting");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Database {
        @Bean @ServiceConnection
        PostgreSQLContainer postgres() {
            return new PostgreSQLContainer(DockerImageName.parse("postgres:17"));
        }
    }

    private static final String CODE = "REAUTH01";
    private static final String CLOSED_CODE = "REAUTH02";

    @Autowired JdbcTemplate sql;
    @Autowired JdbcClient jdbc;
    @Autowired TicketDispatcher dispatcher;
    @Autowired AuthService auth;

    private String course;
    private String closedCourse;

    @BeforeEach void setup() {
        sql.execute("TRUNCATE enrollments, refresh_tokens, sessions, tickets, courses, users CASCADE");
        sql.update("""
            INSERT INTO service_settings(singleton, concurrency_limit, last_changed_by, last_changed_at)
            VALUES (true, 100, NULL, NULL)
            ON CONFLICT (singleton) DO UPDATE SET concurrency_limit=100,
                last_changed_by=NULL, last_changed_at=NULL
            """);
        sql.update("INSERT INTO users VALUES ('prof', 'hash', 'Professor', 'PROFESSOR')");
        course = insertCourse(CODE, "OPEN");
        closedCourse = insertCourse(CLOSED_CODE, "CLOSED");
    }

    private String insertCourse(String code, String status) {
        String id = UUID.randomUUID().toString();
        sql.update("""
            INSERT INTO courses(course_inst_id,course_code,professor_id,course_title,course_category,
              grading_type,max_capacity,min_enrollment,classroom,course_status,course_created_at)
            VALUES (CAST(? AS uuid),?,'prof','Course','MAJOR','ABSOLUTE',3,1,'Room',?,clock_timestamp())
            """, id, code, status);
        return id;
    }

    /** 학생 1명과 세션 1개. admitted가 true면 입장 기록(entered_at)을 남긴다. 세션 ID를 돌려준다 */
    private String student(String user, boolean admitted) {
        sql.update("INSERT INTO users VALUES (?, 'hash', ?, 'STUDENT')", user, user);
        String session = TestFixtures.session(jdbc, user, "STUDENT");
        if (admitted) {
            sql.update("UPDATE sessions SET entered_at=clock_timestamp() WHERE session_id=CAST(? AS uuid)", session);
        }
        return session;
    }

    private long waitingTicket(String type, String user, String session, String courseId, String code) {
        return jdbc.sql("""
                INSERT INTO tickets (request_type, requester_user_id, session_ref, target_course_inst_id,
                                     course_code_snapshot, ticket_issued_at, status_cd)
                VALUES (:type, :user, CAST(:session AS uuid), CAST(:course AS uuid), :code, clock_timestamp(), 'WAITING')
                RETURNING ticket_id""")
            .param("type", type).param("user", user).param("session", session)
            .param("course", courseId).param("code", code)
            .query(Long.class).single();
    }

    private long enrollTicket(String user, String session) {
        return waitingTicket("ENROLLMENT", user, session, course, CODE);
    }

    /** 이미 수강 중인 학생: 근거 티켓·내역(ENROLLED)을 만들고 active_cnt를 1 올린 뒤 WAITING 취소 티켓을 돌려준다 */
    private long cancelTicketOfEnrolled(String user, String session) {
        long source = TestFixtures.succeededEnrollmentTicket(jdbc, user, session, course, CODE);
        sql.update("""
            INSERT INTO enrollments (student_id, course_inst_id, enroll_status, enroll_type, enrolled_at, source_ticket_id)
            VALUES (?, CAST(? AS uuid), 'ENROLLED', 'STUDENT', clock_timestamp(), ?)
            """, user, course, source);
        sql.update("UPDATE courses SET active_cnt = active_cnt + 1 WHERE course_inst_id=CAST(? AS uuid)", course);
        return waitingTicket("CANCELLATION", user, session, course, CODE);
    }

    private void logout(String session) {
        String raw = AuthService.randomToken();
        sql.update("INSERT INTO refresh_tokens(refresh_token_hash,session_id,issued_at) VALUES (?,CAST(? AS uuid),clock_timestamp())",
            AuthService.hash(raw), session);
        auth.logout(raw);
        assertThat(sql.queryForObject("SELECT session_status FROM sessions WHERE session_id=CAST(? AS uuid)", String.class, session))
            .as("실제 로그아웃 경로로 세션이 폐기됐다").isEqualTo("REVOKED");
    }

    private void assertRejected(long ticketId, String result) {
        Map<String, Object> row = sql.queryForMap("""
            SELECT status_cd, result_cd, owner_worker_ref, lease_info, slot_claim_ref, processing_finished_at
            FROM tickets WHERE ticket_id=?""", ticketId);
        assertThat(row).as("ticket %d", ticketId)
            .containsEntry("status_cd", "REJECTED").containsEntry("result_cd", result)
            .containsEntry("owner_worker_ref", null).containsEntry("lease_info", null)
            .containsEntry("slot_claim_ref", null);
        assertThat(row.get("processing_finished_at")).isNotNull();
    }

    private int enrollmentRows(String user) {
        return sql.queryForObject("SELECT count(*) FROM enrollments WHERE student_id=?", Integer.class, user);
    }

    private int enrolledRows(String user) {
        return sql.queryForObject("SELECT count(*) FROM enrollments WHERE student_id=? AND enroll_status='ENROLLED'", Integer.class, user);
    }

    private int activeCnt(String courseId) {
        return sql.queryForObject("SELECT active_cnt FROM courses WHERE course_inst_id=CAST(? AS uuid)", Integer.class, courseId);
    }

    @Test void at05_대기_중_로그아웃한_사용자의_신청과_취소는_E02로_종결된다() {
        String sessionA = student("studentA", true);
        String sessionB = student("studentB", true);
        long enroll = enrollTicket("studentA", sessionA);
        long cancel = cancelTicketOfEnrolled("studentB", sessionB);
        assertThat(activeCnt(course)).isEqualTo(1);

        logout(sessionA);
        logout(sessionB);
        assertThat(dispatcher.claimAndDispatch()).isEqualTo(2);

        assertRejected(enroll, "E02");
        assertRejected(cancel, "E02");
        assertThat(enrollmentRows("studentA")).as("학생 A 내역 없음").isZero();
        assertThat(enrolledRows("studentB")).as("학생 B 내역 그대로").isEqualTo(1);
        assertThat(enrollmentRows("studentB")).isEqualTo(1);
        assertThat(activeCnt(course)).as("active_cnt 그대로").isEqualTo(1);
    }

    @Test void at06_세션_만료와_역할_변경_뒤_실행된_신청과_취소는_E02로_종결된다() {
        String sessionC = student("studentC", true);
        String sessionD = student("studentD", true);
        String sessionE = student("studentE", true);
        String sessionF = student("studentF", true);
        long enrollC = enrollTicket("studentC", sessionC);
        long enrollD = enrollTicket("studentD", sessionD);
        long cancelE = cancelTicketOfEnrolled("studentE", sessionE);
        long cancelF = cancelTicketOfEnrolled("studentF", sessionF);
        assertThat(activeCnt(course)).isEqualTo(2);

        for (String expired : new String[] {sessionC, sessionE}) {
            assertThat(sql.update("""
                UPDATE sessions SET logged_in_at = logged_in_at - interval '169 hours',
                                    expires_at = expires_at - interval '169 hours'
                WHERE session_id = CAST(? AS uuid)""", expired)).isEqualTo(1);
        }
        for (String user : new String[] {"studentD", "studentF"}) {
            assertThat(sql.update("UPDATE users SET role_cd='PROFESSOR' WHERE user_id=?", user)).isEqualTo(1);
        }
        assertThat(dispatcher.claimAndDispatch()).isEqualTo(4);

        for (long id : new long[] {enrollC, enrollD, cancelE, cancelF}) assertRejected(id, "E02");
        assertThat(enrollmentRows("studentC")).as("C 내역 없음").isZero();
        assertThat(enrollmentRows("studentD")).as("D 내역 없음").isZero();
        assertThat(enrolledRows("studentE")).as("E 내역 그대로").isEqualTo(1);
        assertThat(enrolledRows("studentF")).as("F 내역 그대로").isEqualTo(1);
        assertThat(activeCnt(course)).as("active_cnt 그대로").isEqualTo(2);
    }

    @Test void at07_미입장_세션은_E15이고_판정_순서는_E02_E15_E05다() {
        // (a) 미입장 + OPEN 과목 신청
        String sessionA = student("studentA", false);
        long a = enrollTicket("studentA", sessionA);
        // (b) 미입장 + CLOSED 과목 신청
        String sessionB = student("studentB", false);
        long b = waitingTicket("ENROLLMENT", "studentB", sessionB, closedCourse, CLOSED_CODE);
        // (c) 폐기 세션 + CLOSED 과목 신청
        String sessionC = student("studentC", true);
        long c = waitingTicket("ENROLLMENT", "studentC", sessionC, closedCourse, CLOSED_CODE);
        assertThat(sql.update("""
            UPDATE sessions SET session_status='REVOKED', revoked_at=clock_timestamp(), revoke_reason='LOGOUT'
            WHERE session_id=CAST(? AS uuid)""", sessionC)).isEqualTo(1);
        // (d) 미입장 + 수강 중인 OPEN 과목 취소
        String sessionD = student("studentD", false);
        long d = cancelTicketOfEnrolled("studentD", sessionD);
        assertThat(activeCnt(course)).isEqualTo(1);

        assertThat(dispatcher.claimAndDispatch()).isEqualTo(4);

        assertRejected(a, "E15");
        assertRejected(b, "E15");
        assertRejected(c, "E02");
        assertRejected(d, "E15");
        assertThat(enrollmentRows("studentA")).as("(a) 내역 없음").isZero();
        assertThat(enrollmentRows("studentB")).as("(b) 내역 없음").isZero();
        assertThat(enrollmentRows("studentC")).as("(c) 내역 없음").isZero();
        assertThat(enrolledRows("studentD")).as("(d) 내역 그대로").isEqualTo(1);
        assertThat(activeCnt(course)).as("(d) active_cnt 그대로").isEqualTo(1);
        assertThat(activeCnt(closedCourse)).isZero();
    }
}
