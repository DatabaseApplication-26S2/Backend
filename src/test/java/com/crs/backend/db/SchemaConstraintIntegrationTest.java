package com.crs.backend.db;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * X-02 제약 음성 테스트. 기대값은 구현계획-backend.md 3절(3.1~3.9)에서만 끌어냈다.
 *
 * <p>빈 PostgreSQL 17에 Flyway(classpath:db/migration, 시드 없음)를 한 번 적용하고,
 * 케이스마다 트랜잭션 안에서 유효한 기준 행을 넣은 뒤 한 가지만 바꾼 행이 거절되는지 SQLState로 본다.
 * 케이스가 끝나면 트랜잭션을 롤백한다. 그래서 케이스는 서로 독립이고 순서와 무관하다.
 *
 * <p>SQLState: 23514 CHECK, 23505 UNIQUE/PK, 23503 FK, 23502 NOT NULL, 22001 길이 초과, 22003 수 범위 초과.
 * 3.9가 "절차"로 돌린 규칙(R05의 ENROLLMENT·OK, 역할 제한, 종결 결과 불변, E11)은 DB 제약이 아니므로 거절을 기대하지 않는다.
 * 3.9가 "두지 않는다"고 한 {@code active_cnt <= max_capacity}는 수락을 기대한다.
 */
class SchemaConstraintIntegrationTest {

    static final String CHECK = "23514";
    static final String UNIQUE = "23505";
    static final String FK = "23503";
    static final String NOT_NULL = "23502";
    static final String TOO_LONG = "22001";
    static final String OUT_OF_RANGE = "22003";

    static final OffsetDateTime TS = OffsetDateTime.parse("2026-11-20T09:00:00Z");

    static final UUID C1 = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    static final UUID C2 = UUID.fromString("00000000-0000-0000-0000-0000000000c2");
    static final UUID S1 = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    static final UUID CTX_A = UUID.fromString("00000000-0000-0000-0000-00000000c0a1");
    static final UUID CTX_B = UUID.fromString("00000000-0000-0000-0000-00000000c0b1");
    static final UUID CTX_C = UUID.fromString("00000000-0000-0000-0000-00000000c0c1");

    static PostgreSQLContainer postgres;
    Connection conn;

    @BeforeAll
    static void startAndMigrate() {
        postgres = new PostgreSQLContainer("postgres:17");
        postgres.start();
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations("classpath:db/migration")
            .load()
            .migrate();
    }

    @AfterAll
    static void stop() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @BeforeEach
    void openTransactionWithBaseline() throws SQLException {
        conn = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        conn.setAutoCommit(false);
        insertBaseline();
    }

    @AfterEach
    void rollbackAndClose() throws SQLException {
        if (conn != null) {
            conn.rollback();
            conn.close();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 기준 행(유효). 이것이 깨끗이 들어가는 것이 양성 대조다. 순서: 사용자 → 과목 → LOGIN 티켓 → 세션 → 나머지 티켓 → 수강 → 토큰.
    // sessions.login_ticket_id → tickets 와 tickets.session_ref → sessions 는 서로를 가리키므로 LOGIN 티켓이 먼저다.
    // ---------------------------------------------------------------------------------------------
    void insertBaseline() throws SQLException {
        List<Row> rows = List.of(
            userRow("stu01", "STUDENT"),
            userRow("stu02", "STUDENT"),
            userRow("prof01", "PROFESSOR"),
            userRow("adm01", "ADMIN"),
            courseRow().with("course_inst_id", C1).with("course_code", "CS101").with("active_cnt", 2),
            courseRow().with("course_inst_id", C2).with("course_code", "CS102"),
            // LOGIN 티켓 1: 세션 S1이 참조한다
            succeeded(loginTicket()).with("ticket_id", 1L).with("anon_context_id", CTX_A)
                .with("anon_proof_verifier", hex64('a')),
            // LOGIN 티켓 6: 아직 어떤 세션도 참조하지 않는다(세션 케이스용)
            succeeded(loginTicket()).with("ticket_id", 6L).with("anon_context_id", CTX_C)
                .with("anon_proof_verifier", hex64('c')),
            sessionRow().with("session_id", S1).with("user_id", "stu01").with("login_ticket_id", 1L),
            // ENROLLMENT 티켓 2: stu01·C1 (수강 행의 근거)
            succeeded(enrollmentTicket()).with("ticket_id", 2L).with("requester_user_id", "stu01"),
            // 티켓 3: stu01·C2 (enrRow()의 근거)
            succeeded(enrollmentTicket()).with("ticket_id", 3L).with("requester_user_id", "stu01")
                .with("target_course_inst_id", C2).with("course_code_snapshot", "CS102"),
            // 티켓 4: stu01·C1, 어떤 수강 행도 쓰지 않는다(다른 과목 근거 케이스용)
            succeeded(enrollmentTicket()).with("ticket_id", 4L).with("requester_user_id", "stu01"),
            enrRow().with("course_inst_id", C1).with("source_ticket_id", 2L),
            adminEnrRow().with("course_inst_id", C1),
            refreshRow().with("refresh_token_hash", hex64('e')).with("consumed_at", null)
        );
        for (Row row : rows) {
            exec(row.sql(), row.params());
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 행 빌더. 열 이름 = 도메인정의서 5장 물리명. with()는 없는 열 이름을 거부한다(오타 방지).
    // ---------------------------------------------------------------------------------------------
    record Row(String table, Map<String, Object> values) {
        static Row of(String table, Object... keyValues) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (int i = 0; i < keyValues.length; i += 2) {
                map.put((String) keyValues[i], keyValues[i + 1]);
            }
            return new Row(table, map);
        }

        Row with(String column, Object value) {
            if (!values.containsKey(column)) {
                throw new IllegalArgumentException("unknown column " + table + "." + column);
            }
            Map<String, Object> copy = new LinkedHashMap<>(values);
            copy.put(column, value);
            return new Row(table, copy);
        }

        Row without(String column) {
            if (!values.containsKey(column)) {
                throw new IllegalArgumentException("unknown column " + table + "." + column);
            }
            Map<String, Object> copy = new LinkedHashMap<>(values);
            copy.remove(column);
            return new Row(table, copy);
        }

        String sql() {
            return "INSERT INTO " + table + " (" + String.join(", ", values.keySet()) + ") VALUES ("
                + String.join(", ", Collections.nCopies(values.size(), "?")) + ")";
        }

        Object[] params() {
            return values.values().toArray();
        }
    }

    static String hex64(char c) {
        return String.valueOf(c).repeat(64);
    }

    static Row userRow(String id, String role) {
        return Row.of("users",
            "user_id", id,
            "password_hash", "hash-" + id,
            "user_name", "name-" + id,
            "role_cd", role);
    }

    /** 유효한 OPEN 과목. 코드 CS200, 인원 0. */
    static Row courseRow() {
        return Row.of("courses",
            "course_inst_id", UUID.randomUUID(),
            "course_code", "CS200",
            "professor_id", "prof01",
            "course_title", "데이터베이스",
            "course_category", "GENERAL",
            "grading_type", "RELATIVE",
            "max_capacity", 30,
            "min_enrollment", 5,
            "active_cnt", 0,
            "classroom", "R201",
            "course_status", "OPEN",
            "course_created_at", TS,
            "course_updated_at", null);
    }

    /** 유효한 STUDENT 수강 행: stu01·C2, 근거 티켓 3(stu01·C2). */
    static Row enrRow() {
        return Row.of("enrollments",
            "student_id", "stu01",
            "course_inst_id", C2,
            "enroll_status", "ENROLLED",
            "enroll_type", "STUDENT",
            "enrolled_at", TS,
            "assigned_by_user_id", null,
            "source_ticket_id", 3L);
    }

    /** 유효한 ADMIN 수강 행: stu02·C2, 배정 관리자 adm01, 근거 티켓 없음. */
    static Row adminEnrRow() {
        return Row.of("enrollments",
            "student_id", "stu02",
            "course_inst_id", C2,
            "enroll_status", "ENROLLED",
            "enroll_type", "ADMIN",
            "enrolled_at", TS,
            "assigned_by_user_id", "adm01",
            "source_ticket_id", null);
    }

    /** 유효한 WAITING ENROLLMENT 티켓: stu02·C1, 세션 S1. 미완결이다. */
    static Row enrollmentTicket() {
        return Row.of("tickets",
            "ticket_id", 10L,
            "request_type", "ENROLLMENT",
            "requester_user_id", "stu02",
            "session_ref", S1,
            "anon_context_id", null,
            "anon_proof_verifier", null,
            "credential_expires_at", null,
            "proof_expires_at", null,
            "target_course_inst_id", C1,
            "course_code_snapshot", "CS101",
            "ticket_issued_at", TS,
            "status_cd", "WAITING",
            "processing_started_at", null,
            "processing_finished_at", null,
            "result_cd", null,
            "retry_count", 0,
            "owner_worker_ref", null,
            "generation_no", 0,
            "lease_info", null,
            "slot_claim_ref", null);
    }

    static Row cancellationTicket() {
        return enrollmentTicket().with("request_type", "CANCELLATION");
    }

    static Row admissionTicket() {
        return enrollmentTicket().with("request_type", "ADMISSION")
            .with("target_course_inst_id", null).with("course_code_snapshot", null);
    }

    /** 유효한 WAITING LOGIN 티켓. 익명 4개 컬럼이 있고 요청자·세션·대상 과목이 없다. */
    static Row loginTicket() {
        return enrollmentTicket()
            .with("ticket_id", 11L)
            .with("request_type", "LOGIN")
            .with("requester_user_id", null)
            .with("session_ref", null)
            .with("anon_context_id", CTX_B)
            .with("anon_proof_verifier", hex64('b'))
            .with("credential_expires_at", TS.plusMinutes(5))
            .with("proof_expires_at", TS.plusMinutes(10))
            .with("target_course_inst_id", null)
            .with("course_code_snapshot", null);
    }

    static Row ticketOf(String requestType) {
        return switch (requestType) {
            case "LOGIN" -> loginTicket();
            case "ADMISSION" -> admissionTicket();
            case "ENROLLMENT" -> enrollmentTicket();
            case "CANCELLATION" -> cancellationTicket();
            default -> throw new IllegalArgumentException(requestType);
        };
    }

    static Row succeeded(Row ticket) {
        return ticket.with("status_cd", "SUCCEEDED").with("result_cd", "OK").with("processing_finished_at", TS);
    }

    static Row rejected(Row ticket, String resultCd) {
        return ticket.with("status_cd", "REJECTED").with("result_cd", resultCd).with("processing_finished_at", TS);
    }

    /** generation_no 0 · slot_claim_ref 0 인 PROCESSING. */
    static Row processing(Row ticket) {
        return ticket.with("status_cd", "PROCESSING")
            .with("processing_started_at", TS)
            .with("owner_worker_ref", "worker-1")
            .with("lease_info", TS.plusSeconds(30))
            .with("slot_claim_ref", 0);
    }

    /** 유효한 ACTIVE STUDENT 세션: stu02, 근거 LOGIN 티켓 6, 만료 = 로그인 + 168시간. */
    static Row sessionRow() {
        return Row.of("sessions",
            "session_id", UUID.randomUUID(),
            "user_id", "stu02",
            "role_cd", "STUDENT",
            "login_ticket_id", 6L,
            "logged_in_at", TS,
            "expires_at", TS.plusHours(168),
            "session_status", "ACTIVE",
            "revoked_at", null,
            "revoke_reason", null,
            "first_token_issued_at", null,
            "entered_at", null);
    }

    /** 유효한 소비된 Refresh 기록(세션 S1). 소비된 기록은 세션당 여러 건 가능하다. */
    static Row refreshRow() {
        return Row.of("refresh_tokens",
            "refresh_token_hash", hex64('d'),
            "session_id", S1,
            "issued_at", TS,
            "consumed_at", TS);
    }

    static <T> Stream<Arguments> cross(List<T> first, List<?> second) {
        return first.stream().flatMap(a -> second.stream().map(b -> Arguments.of(a, b)));
    }

    // ---------------------------------------------------------------------------------------------
    // JDBC 보조. 거절은 세이브포인트 안에서 확인하므로 한 케이스에서 여러 번 거절을 볼 수 있다.
    // ---------------------------------------------------------------------------------------------
    int exec(String sql, Object... params) throws SQLException {
        try (var ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            return ps.executeUpdate();
        }
    }

    Object scalar(String sql, Object... params) throws SQLException {
        try (var ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (var rs = ps.executeQuery()) {
                assertTrue(rs.next(), "no row: " + sql);
                return rs.getObject(1);
            }
        }
    }

    void assertRejected(String sqlState, String sql, Object... params) throws SQLException {
        Savepoint savepoint = conn.setSavepoint();
        try {
            exec(sql, params);
        } catch (SQLException e) {
            conn.rollback(savepoint);
            assertEquals(sqlState, e.getSQLState(),
                () -> "SQLState 불일치: " + e.getMessage() + "\n" + sql + "\n" + Arrays.toString(params));
            return;
        }
        conn.rollback(savepoint);
        fail("거절돼야 하는데 수락됨: " + sql + "\n" + Arrays.toString(params));
    }

    void assertRejected(String sqlState, Row row) throws SQLException {
        assertRejected(sqlState, row.sql(), row.params());
    }

    void assertAccepted(String sql, Object... params) throws SQLException {
        Savepoint savepoint = conn.setSavepoint();
        try {
            exec(sql, params);
        } catch (SQLException e) {
            conn.rollback(savepoint);
            fail("수락돼야 하는데 거절됨(" + e.getSQLState() + "): " + e.getMessage() + "\n" + sql
                + "\n" + Arrays.toString(params));
        }
    }

    void assertAccepted(Row row) throws SQLException {
        assertAccepted(row.sql(), row.params());
    }

    // =============================================================================================
    @Nested
    @DisplayName("스키마 형태 (3절 머리)")
    class SchemaShape {
        @Test
        @DisplayName("기준 행 묶음이 모두 들어간다(양성 대조)")
        void 기준_행이_들어간다() throws SQLException {
            assertEquals(4L, scalar("SELECT count(*) FROM users"));
            assertEquals(2L, scalar("SELECT count(*) FROM courses"));
            assertEquals(5L, scalar("SELECT count(*) FROM tickets"));
            assertEquals(1L, scalar("SELECT count(*) FROM sessions"));
            assertEquals(2L, scalar("SELECT count(*) FROM enrollments"));
            assertEquals(1L, scalar("SELECT count(*) FROM refresh_tokens"));
        }

        @Test
        @DisplayName("테이블은 정확히 7개이고 이름이 고정이다")
        void 테이블_7개() throws SQLException {
            assertEquals(7L, scalar("""
                SELECT count(*) FROM information_schema.tables
                 WHERE table_schema = current_schema() AND table_type = 'BASE TABLE'
                   AND table_name <> 'flyway_schema_history'"""));
            assertEquals(7L, scalar("""
                SELECT count(*) FROM information_schema.tables
                 WHERE table_schema = current_schema()
                   AND table_name IN ('users','courses','enrollments','tickets',
                                      'service_settings','sessions','refresh_tokens')"""));
        }

        @ParameterizedTest(name = "{0} = {1}개 컬럼")
        @CsvSource({"users,4", "courses,13", "enrollments,7", "tickets,20",
            "service_settings,4", "sessions,11", "refresh_tokens,4"})
        void 컬럼_수(String table, long expected) throws SQLException {
            assertEquals(expected, scalar("""
                SELECT count(*) FROM information_schema.columns
                 WHERE table_schema = current_schema() AND table_name = ?""", table));
        }

        @Test
        @DisplayName("컬럼 합계는 63개(속성 62 + singleton)")
        void 컬럼_63개() throws SQLException {
            assertEquals(63L, scalar("""
                SELECT count(*) FROM information_schema.columns
                 WHERE table_schema = current_schema()
                   AND table_name IN ('users','courses','enrollments','tickets',
                                      'service_settings','sessions','refresh_tokens')"""));
        }

        @Test
        @DisplayName("시퀀스 ticket_id_seq가 있고 양수를 낸다")
        void 시퀀스() throws SQLException {
            Object value = scalar("SELECT nextval('ticket_id_seq')");
            assertTrue(((Number) value).longValue() > 0, "ticket_id_seq must yield a positive value");
        }
    }

    // =============================================================================================
    @Nested
    @DisplayName("users (3.1)")
    class Users {
        @Test
        @DisplayName("user_id 중복은 PK 위반")
        void PK_중복() throws SQLException {
            assertRejected(UNIQUE, userRow("stu01", "STUDENT"));
        }

        @ParameterizedTest(name = "role_cd = ''{0}''")
        @ValueSource(strings = {"GUEST", "student", "TEACHER", ""})
        void 역할_집합_밖(String role) throws SQLException {
            assertRejected(CHECK, userRow("u-new", "STUDENT").with("role_cd", role));
        }

        @Test
        @DisplayName("role_cd는 기본값이 없어 생략하면 NOT NULL 위반")
        void 역할_생략() throws SQLException {
            assertRejected(NOT_NULL, userRow("u-new", "STUDENT").without("role_cd"));
        }

        @Test
        @DisplayName("집합 안의 세 역할은 모두 수락")
        void 세_역할_수락() throws SQLException {
            assertAccepted(userRow("u-s", "STUDENT"));
            assertAccepted(userRow("u-p", "PROFESSOR"));
            assertAccepted(userRow("u-a", "ADMIN"));
        }

        @Test
        @DisplayName("길이 한도(20/100/50) 초과는 거절, 한도 값은 수락")
        void 길이_한도() throws SQLException {
            assertRejected(TOO_LONG, userRow("x".repeat(21), "STUDENT"));
            assertRejected(TOO_LONG, userRow("u-new", "STUDENT").with("password_hash", "h".repeat(101)));
            assertRejected(TOO_LONG, userRow("u-new", "STUDENT").with("user_name", "n".repeat(51)));
            assertAccepted(userRow("x".repeat(20), "STUDENT")
                .with("password_hash", "h".repeat(100)).with("user_name", "n".repeat(50)));
        }
    }

    // =============================================================================================
    @Nested
    @DisplayName("courses (3.2)")
    class Courses {
        @Test
        @DisplayName("course_code 중복은 UNIQUE 위반")
        void 코드_UNIQUE() throws SQLException {
            assertRejected(UNIQUE, courseRow().with("course_code", "CS101"));
        }

        @Test
        @DisplayName("course_code는 COLLATE C라 대소문자를 구분해 수락")
        void 코드_대소문자_구분() throws SQLException {
            assertAccepted(courseRow().with("course_code", "cs101"));
        }

        @ParameterizedTest(name = "course_code = [{0}]")
        @ValueSource(strings = {"CS-101", "", " ", "CS 101", "한글과목", "CS101\n", "-"})
        void 코드_정규식_위반(String code) throws SQLException {
            assertRejected(CHECK, courseRow().with("course_code", code));
        }

        @Test
        @DisplayName("course_code 11자는 거절(varchar(10) 길이 초과), 10자와 1자는 수락")
        void 코드_길이_경계() throws SQLException {
            assertRejected(TOO_LONG, courseRow().with("course_code", "ABCDEFGHIJK"));
            assertAccepted(courseRow().with("course_code", "ABCDEFGHIJ"));
            assertAccepted(courseRow().with("course_code", "Z"));
        }

        @ParameterizedTest(name = "course_title = [{0}]")
        @ValueSource(strings = {"", " ", "   ", "\t\n"})
        void 제목_공백만(String title) throws SQLException {
            assertRejected(CHECK, courseRow().with("course_title", title));
        }

        @ParameterizedTest(name = "classroom = [{0}]")
        @ValueSource(strings = {"", " ", "   ", "\t\n"})
        void 강의실_공백만(String classroom) throws SQLException {
            assertRejected(CHECK, courseRow().with("classroom", classroom));
        }

        @ParameterizedTest(name = "course_category = {0}")
        @ValueSource(strings = {"ELECTIVE", "major", ""})
        void 분류_집합_밖(String value) throws SQLException {
            assertRejected(CHECK, courseRow().with("course_category", value));
        }

        @ParameterizedTest(name = "grading_type = {0}")
        @ValueSource(strings = {"LETTER", "PASSFAIL", "relative", ""})
        void 성적방식_집합_밖(String value) throws SQLException {
            assertRejected(CHECK, courseRow().with("grading_type", value));
        }

        @Test
        @DisplayName("집합 안의 분류·성적방식 조합은 모두 수락")
        void 집합_안_수락() throws SQLException {
            assertAccepted(courseRow().with("course_code", "K1").with("course_category", "MAJOR")
                .with("grading_type", "ABSOLUTE"));
            assertAccepted(courseRow().with("course_code", "K2").with("grading_type", "PASS_FAIL"));
        }

        @ParameterizedTest(name = "max_capacity = {0}")
        @ValueSource(ints = {0, -1})
        void 정원_1_미만(int value) throws SQLException {
            assertRejected(CHECK, courseRow().with("max_capacity", value).with("min_enrollment", 1));
        }

        @ParameterizedTest(name = "min_enrollment = {0}")
        @ValueSource(ints = {0, -1})
        void 최소인원_1_미만(int value) throws SQLException {
            assertRejected(CHECK, courseRow().with("min_enrollment", value));
        }

        @Test
        @DisplayName("min_enrollment가 max_capacity보다 크면 거절, 같거나 1이면 수락")
        void 최소인원_정원_초과() throws SQLException {
            assertRejected(CHECK, courseRow().with("min_enrollment", 31));
            assertAccepted(courseRow().with("course_code", "K1").with("min_enrollment", 30));
            assertAccepted(courseRow().with("course_code", "K2").with("min_enrollment", 1));
            assertAccepted(courseRow().with("course_code", "K3").with("max_capacity", 1).with("min_enrollment", 1));
        }

        @Test
        @DisplayName("정수 상한 2147483647은 수락, 초과는 수 범위 오류")
        void 정수_상한() throws SQLException {
            assertAccepted(courseRow().with("max_capacity", Integer.MAX_VALUE));
            assertRejected(OUT_OF_RANGE, courseRow().with("max_capacity", 2147483648L));
            assertRejected(OUT_OF_RANGE, courseRow().with("min_enrollment", 2147483648L));
        }

        @Test
        @DisplayName("active_cnt -1은 거절")
        void 인원_음수() throws SQLException {
            assertRejected(CHECK, courseRow().with("active_cnt", -1));
        }

        @ParameterizedTest(name = "course_status = {0}")
        @ValueSource(strings = {"ARCHIVED", "open", ""})
        void 상태_집합_밖(String value) throws SQLException {
            assertRejected(CHECK, courseRow().with("course_status", value));
        }

        @Test
        @DisplayName("INV-8: CLOSED인데 active_cnt가 1이면 거절, 0이면 수락")
        void CLOSED_인원() throws SQLException {
            assertRejected(CHECK, courseRow().with("course_status", "CLOSED").with("active_cnt", 1));
            assertRejected(CHECK, courseRow().with("course_status", "CLOSED").with("active_cnt", 31));
            assertAccepted(courseRow().with("course_status", "CLOSED").with("active_cnt", 0));
        }

        @Test
        @DisplayName("INV-8: 인원이 남은 OPEN 과목을 UPDATE로 CLOSED로 바꾸면 거절")
        void CLOSED_UPDATE() throws SQLException {
            assertRejected(CHECK, """
                UPDATE courses SET course_status = 'CLOSED' WHERE course_inst_id = ?""", C1);
            // active_cnt를 0으로 내리지 않은 채로는 닫을 수 없고, 0이면 닫을 수 있다
            assertAccepted("""
                UPDATE courses SET active_cnt = 0, course_status = 'CLOSED' WHERE course_inst_id = ?""", C2);
        }

        @Test
        @DisplayName("3.9: active_cnt가 max_capacity를 넘는 행은 수락(CHECK를 두지 않는다)")
        void 인원_정원_초과_수락() throws SQLException {
            assertAccepted(courseRow().with("active_cnt", 31));
            assertAccepted(courseRow().with("course_code", "K1").with("active_cnt", 1000));
        }

        @Test
        @DisplayName("3.9: 정원을 넘긴 과목의 인원 UPDATE·정원 축소 UPDATE도 수락")
        void 인원_정원_초과_UPDATE() throws SQLException {
            assertAccepted("UPDATE courses SET active_cnt = 100 WHERE course_inst_id = ?", C1);
            assertAccepted("""
                UPDATE courses SET max_capacity = 1, min_enrollment = 1 WHERE course_inst_id = ?""", C1);
            assertAccepted("UPDATE courses SET course_title = '변경' WHERE course_inst_id = ?", C1);
        }

        @Test
        @DisplayName("professor_id가 users에 없으면 FK 위반")
        void 담당교수_FK() throws SQLException {
            assertRejected(FK, courseRow().with("professor_id", "nobody"));
        }

        @Test
        @DisplayName("기본값: course_inst_id=gen_random_uuid(), active_cnt=0, course_status=OPEN")
        void 기본값() throws SQLException {
            assertAccepted("""
                INSERT INTO courses (course_code, professor_id, course_title, course_category, grading_type,
                                     max_capacity, min_enrollment, classroom, course_created_at)
                VALUES ('DEF1', 'prof01', '기본값', 'MAJOR', 'ABSOLUTE', 10, 1, 'R1', now())""");
            assertEquals(0, scalar("SELECT active_cnt FROM courses WHERE course_code = 'DEF1'"));
            assertEquals("OPEN", scalar("SELECT course_status FROM courses WHERE course_code = 'DEF1'"));
            assertTrue(scalar("SELECT course_inst_id FROM courses WHERE course_code = 'DEF1'") != null);
        }

        @Test
        @DisplayName("course_updated_at은 NULL 허용(기준 행이 NULL로 수락)")
        void 수정시각_NULL() throws SQLException {
            assertAccepted(courseRow().with("course_code", "K1").with("course_updated_at", null));
            assertAccepted(courseRow().with("course_code", "K2").with("course_updated_at", TS));
        }
    }

    // =============================================================================================
    @Nested
    @DisplayName("enrollments (3.3)")
    class Enrollments {
        @Test
        @DisplayName("(student_id, course_inst_id) 중복은 PK 위반")
        void PK_중복() throws SQLException {
            // 기준 행에 stu02·C1(ADMIN)이 있다
            assertRejected(UNIQUE, adminEnrRow().with("course_inst_id", C1));
        }

        @ParameterizedTest(name = "enroll_status = {0}")
        @ValueSource(strings = {"DROPPED", "enrolled", ""})
        void 상태_집합_밖(String value) throws SQLException {
            assertRejected(CHECK, enrRow().with("enroll_status", value));
        }

        @ParameterizedTest(name = "enroll_type = {0}")
        @ValueSource(strings = {"TEACHER", "admin", ""})
        void 구분_집합_밖(String value) throws SQLException {
            assertRejected(CHECK, enrRow().with("enroll_type", value));
            assertRejected(CHECK, adminEnrRow().with("enroll_type", value));
        }

        @Test
        @DisplayName("enroll_type은 기본값이 없어 생략하면 NOT NULL 위반")
        void 구분_생략() throws SQLException {
            assertRejected(NOT_NULL, enrRow().without("enroll_type"));
        }

        @Test
        @DisplayName("enroll_status 기본값은 ENROLLED, CLOSED도 수락")
        void 상태_기본값과_CLOSED() throws SQLException {
            assertAccepted(adminEnrRow().without("enroll_status"));
            assertEquals("ENROLLED", scalar("""
                SELECT enroll_status FROM enrollments WHERE student_id = 'stu02' AND course_inst_id = ?""", C2));
            assertAccepted(enrRow().with("enroll_status", "CLOSED"));
        }

        @Test
        @DisplayName("CHECK: ADMIN인데 배정 관리자가 없으면 거절(NULL 조합)")
        void ADMIN_배정자_없음() throws SQLException {
            assertRejected(CHECK, adminEnrRow().with("assigned_by_user_id", null));
        }

        @Test
        @DisplayName("CHECK: ADMIN인데 근거 티켓이 있으면 거절(배정자도 있는 조합)")
        void ADMIN_근거티켓_있음() throws SQLException {
            assertRejected(CHECK, adminEnrRow().with("source_ticket_id", 3L));
        }

        @Test
        @DisplayName("CHECK: ADMIN인데 배정자 없이 근거 티켓만 있으면 거절")
        void ADMIN_근거티켓만() throws SQLException {
            assertRejected(CHECK, adminEnrRow().with("assigned_by_user_id", null).with("source_ticket_id", 3L));
        }

        @Test
        @DisplayName("CHECK: STUDENT인데 근거 티켓이 없으면 거절(NULL 조합)")
        void STUDENT_근거티켓_없음() throws SQLException {
            assertRejected(CHECK, enrRow().with("source_ticket_id", null));
        }

        @Test
        @DisplayName("CHECK: STUDENT인데 배정 관리자가 있으면 거절(근거 티켓도 있는 조합)")
        void STUDENT_배정자_있음() throws SQLException {
            assertRejected(CHECK, enrRow().with("assigned_by_user_id", "adm01"));
        }

        @Test
        @DisplayName("CHECK: STUDENT인데 근거 티켓 없이 배정자만 있으면 거절")
        void STUDENT_배정자만() throws SQLException {
            assertRejected(CHECK, enrRow().with("source_ticket_id", null).with("assigned_by_user_id", "adm01"));
        }

        @Test
        @DisplayName("CHECK: 두 컬럼이 모두 NULL인 ADMIN·STUDENT 행은 거절")
        void 둘다_NULL() throws SQLException {
            assertRejected(CHECK, adminEnrRow().with("assigned_by_user_id", null).with("source_ticket_id", null));
            assertRejected(CHECK, enrRow().with("assigned_by_user_id", null).with("source_ticket_id", null));
        }

        @Test
        @DisplayName("MATCH SIMPLE: source_ticket_id가 NULL인 ADMIN 행은 복합 FK를 검사하지 않고 수락")
        void ADMIN_NULL_근거_수락() throws SQLException {
            assertAccepted(adminEnrRow());
            // 근거 티켓과 무관한 학생·과목이라도 ADMIN이면 수락
            assertAccepted(adminEnrRow().with("student_id", "stu01").with("course_inst_id", C2));
        }

        @Test
        @DisplayName("source_ticket_id UNIQUE: 이미 쓰인 근거 티켓은 거절(복합 FK 때문에 PK 중복과 겹친다)")
        void 근거티켓_UNIQUE() throws SQLException {
            // 티켓 2(stu01·C1)는 기준 수강 행 stu01·C1이 쓴다. 복합 FK가 같은 (학생, 과목)만 허용하므로
            // 근거 티켓 중복은 PK 중복과 함께만 만들어진다. 어느 쪽이든 23505다.
            assertRejected(UNIQUE, enrRow().with("course_inst_id", C1).with("source_ticket_id", 2L));
        }

        @Test
        @DisplayName("복합 FK: 근거 티켓이 다른 학생의 것이면 거절")
        void 복합FK_다른_학생() throws SQLException {
            // 티켓 3의 요청자는 stu01이다
            assertRejected(FK, enrRow().with("student_id", "stu02"));
        }

        @Test
        @DisplayName("복합 FK: 근거 티켓이 다른 과목의 것이면 거절")
        void 복합FK_다른_과목() throws SQLException {
            // 티켓 4는 stu01·C1이고 아직 쓰이지 않았다. 행은 stu01·C2
            assertRejected(FK, enrRow().with("source_ticket_id", 4L));
        }

        @Test
        @DisplayName("복합 FK: 존재하지 않는 근거 티켓은 거절")
        void 복합FK_없는_티켓() throws SQLException {
            assertRejected(FK, enrRow().with("source_ticket_id", 9999L));
        }

        @Test
        @DisplayName("복합 FK: 학생·과목·티켓이 모두 맞으면 수락")
        void 복합FK_일치_수락() throws SQLException {
            assertAccepted(enrRow());
        }

        @Test
        @DisplayName("FK: student_id가 users에 없으면 거절")
        void 학생_FK() throws SQLException {
            assertRejected(FK, adminEnrRow().with("student_id", "nobody"));
        }

        @Test
        @DisplayName("FK: course_inst_id가 courses에 없으면 거절")
        void 과목_FK() throws SQLException {
            assertRejected(FK, adminEnrRow().with("course_inst_id", UUID.randomUUID()));
        }

        @Test
        @DisplayName("FK: assigned_by_user_id가 users에 없으면 거절")
        void 배정자_FK() throws SQLException {
            assertRejected(FK, adminEnrRow().with("assigned_by_user_id", "nobody"));
        }
    }

    // =============================================================================================
    @Nested
    @DisplayName("tickets (3.4)")
    class Tickets {
        // ----- PK / ticket_id ---------------------------------------------------------------
        @ParameterizedTest(name = "ticket_id = {0}")
        @ValueSource(longs = {0L, -1L, Long.MIN_VALUE})
        void 번호_양수가_아님(long id) throws SQLException {
            assertRejected(CHECK, enrollmentTicket().with("ticket_id", id));
        }

        @Test
        @DisplayName("ticket_id 중복은 PK 위반")
        void 번호_PK_중복() throws SQLException {
            assertRejected(UNIQUE, enrollmentTicket().with("ticket_id", 2L));
        }

        @Test
        @DisplayName("ticket_id가 1이면 수락, 최댓값도 수락")
        void 번호_경계_수락() throws SQLException {
            assertAccepted(enrollmentTicket().with("ticket_id", 100L));
            assertAccepted(loginTicket().with("ticket_id", Long.MAX_VALUE));
        }

        // ----- 값 집합 ------------------------------------------------------------------------
        @ParameterizedTest(name = "request_type = {0}")
        @ValueSource(strings = {"REFUND", "login", ""})
        void 유형_집합_밖(String value) throws SQLException {
            assertRejected(CHECK, enrollmentTicket().with("request_type", value));
        }

        @ParameterizedTest(name = "status_cd = {0}")
        @ValueSource(strings = {"DONE", "waiting", "EXPIRED", ""})
        void 상태_집합_밖(String value) throws SQLException {
            assertRejected(CHECK, enrollmentTicket().with("status_cd", value));
        }

        @Test
        @DisplayName("retry_count -1은 거절, 0과 양수는 수락")
        void 재시도_음수() throws SQLException {
            assertRejected(CHECK, enrollmentTicket().with("retry_count", -1));
            assertAccepted(enrollmentTicket().with("retry_count", 0));
            assertAccepted(enrollmentTicket().with("ticket_id", 12L).with("requester_user_id", "stu01")
                .with("retry_count", 7));
        }

        @Test
        @DisplayName("기본값: status_cd=RECEIVED, retry_count=0, generation_no=0")
        void 기본값() throws SQLException {
            Row row = enrollmentTicket().without("status_cd").without("retry_count").without("generation_no");
            assertAccepted(row);
            assertEquals("RECEIVED", scalar("SELECT status_cd FROM tickets WHERE ticket_id = 10"));
            assertEquals(0, scalar("SELECT retry_count FROM tickets WHERE ticket_id = 10"));
            assertEquals(0, scalar("SELECT generation_no FROM tickets WHERE ticket_id = 10"));
        }

        @Test
        @DisplayName("RECEIVED 상태(결과·완료 시각 없음)는 수락")
        void RECEIVED_수락() throws SQLException {
            assertAccepted(enrollmentTicket().with("status_cd", "RECEIVED"));
        }

        @Test
        @DisplayName("길이 한도: anon_proof_verifier 65자, course_code_snapshot 11자, result_cd 11자, owner_worker_ref 65자는 거절")
        void 길이_한도() throws SQLException {
            assertRejected(TOO_LONG, loginTicket().with("anon_proof_verifier", "f".repeat(65)));
            assertRejected(TOO_LONG, enrollmentTicket().with("course_code_snapshot", "ABCDEFGHIJK"));
            assertRejected(TOO_LONG, rejected(enrollmentTicket(), "E0000000000"));
            assertRejected(TOO_LONG, processing(enrollmentTicket()).with("owner_worker_ref", "w".repeat(65)));
            assertAccepted(processing(enrollmentTicket()).with("owner_worker_ref", "w".repeat(64)));
        }

        // ----- CHECK 1: LOGIN <-> 익명 4개 컬럼 / 요청자·세션 ---------------------------------------------
        @ParameterizedTest(name = "LOGIN에서 {0}가 없으면 거절")
        @ValueSource(strings = {"anon_context_id", "anon_proof_verifier", "credential_expires_at", "proof_expires_at"})
        void CHECK1_LOGIN_익명_누락(String column) throws SQLException {
            assertRejected(CHECK, loginTicket().with(column, null));
        }

        @Test
        @DisplayName("CHECK 1: LOGIN인데 익명 4개가 모두 없으면 거절")
        void CHECK1_LOGIN_익명_전부_없음() throws SQLException {
            assertRejected(CHECK, loginTicket().with("anon_context_id", null).with("anon_proof_verifier", null)
                .with("credential_expires_at", null).with("proof_expires_at", null));
        }

        @Test
        @DisplayName("CHECK 1: LOGIN에 요청자가 있으면 거절")
        void CHECK1_LOGIN_요청자() throws SQLException {
            assertRejected(CHECK, loginTicket().with("requester_user_id", "stu01"));
        }

        @Test
        @DisplayName("CHECK 1: LOGIN에 세션이 있으면 거절")
        void CHECK1_LOGIN_세션() throws SQLException {
            assertRejected(CHECK, loginTicket().with("session_ref", S1));
        }

        @Test
        @DisplayName("CHECK 1: LOGIN에 요청자와 세션이 모두 있으면 거절")
        void CHECK1_LOGIN_요청자_세션() throws SQLException {
            assertRejected(CHECK, loginTicket().with("requester_user_id", "stu01").with("session_ref", S1));
        }

        static Stream<Arguments> 비LOGIN_유형_x_익명_컬럼() {
            return cross(List.of("ADMISSION", "ENROLLMENT", "CANCELLATION"),
                List.of("anon_context_id", "anon_proof_verifier", "credential_expires_at", "proof_expires_at"));
        }

        @ParameterizedTest(name = "{0}에 {1}가 있으면 거절")
        @MethodSource("com.crs.backend.db.SchemaConstraintIntegrationTest$Tickets#비LOGIN_유형_x_익명_컬럼")
        void CHECK1_비LOGIN_익명_존재(String type, String column) throws SQLException {
            Object value = loginTicket().values().get(column);
            assertRejected(CHECK, ticketOf(type).with(column, value));
        }

        @ParameterizedTest(name = "{0}에서 {1}가 없으면 거절")
        @MethodSource("com.crs.backend.db.SchemaConstraintIntegrationTest$Tickets#비LOGIN_유형_x_요청자_세션")
        void CHECK1_비LOGIN_요청자_세션_누락(String type, String column) throws SQLException {
            assertRejected(CHECK, ticketOf(type).with(column, null));
        }

        static Stream<Arguments> 비LOGIN_유형_x_요청자_세션() {
            return cross(List.of("ADMISSION", "ENROLLMENT", "CANCELLATION"),
                List.of("requester_user_id", "session_ref"));
        }

        @Test
        @DisplayName("CHECK 1: 비LOGIN에서 요청자·세션이 모두 없으면 거절")
        void CHECK1_비LOGIN_둘다_없음() throws SQLException {
            assertRejected(CHECK, enrollmentTicket().with("requester_user_id", null).with("session_ref", null));
        }

        // ----- CHECK 2: 대상 과목·코드 기록 ------------------------------------------------------
        @ParameterizedTest(name = "{0}에서 {1}가 없으면 거절")
        @MethodSource("com.crs.backend.db.SchemaConstraintIntegrationTest$Tickets#과목_유형_x_대상_컬럼")
        void CHECK2_대상_누락(String type, String column) throws SQLException {
            assertRejected(CHECK, ticketOf(type).with(column, null));
        }

        static Stream<Arguments> 과목_유형_x_대상_컬럼() {
            return cross(List.of("ENROLLMENT", "CANCELLATION"),
                List.of("target_course_inst_id", "course_code_snapshot"));
        }

        @ParameterizedTest(name = "{0}에서 대상 과목·코드 기록이 모두 없으면 거절")
        @ValueSource(strings = {"ENROLLMENT", "CANCELLATION"})
        void CHECK2_대상_둘다_없음(String type) throws SQLException {
            assertRejected(CHECK, ticketOf(type).with("target_course_inst_id", null)
                .with("course_code_snapshot", null));
        }

        @ParameterizedTest(name = "{0}에 {1}가 있으면 거절")
        @MethodSource("com.crs.backend.db.SchemaConstraintIntegrationTest$Tickets#비과목_유형_x_대상_컬럼")
        void CHECK2_대상_존재(String type, String column) throws SQLException {
            Object value = enrollmentTicket().values().get(column);
            assertRejected(CHECK, ticketOf(type).with(column, value));
        }

        static Stream<Arguments> 비과목_유형_x_대상_컬럼() {
            return cross(List.of("LOGIN", "ADMISSION"), List.of("target_course_inst_id", "course_code_snapshot"));
        }

        @Test
        @DisplayName("CHECK 2: LOGIN·ADMISSION에 대상 과목과 코드가 모두 있으면 거절")
        void CHECK2_대상_둘다_존재() throws SQLException {
            assertRejected(CHECK, loginTicket().with("target_course_inst_id", C1).with("course_code_snapshot", "CS101"));
            assertRejected(CHECK, admissionTicket().with("target_course_inst_id", C1)
                .with("course_code_snapshot", "CS101"));
        }

        @Test
        @DisplayName("네 유형의 올바른 형태는 모두 수락")
        void 네_유형_수락() throws SQLException {
            assertAccepted(loginTicket().with("ticket_id", 21L));
            assertAccepted(admissionTicket().with("ticket_id", 22L).with("requester_user_id", "stu01"));
            assertAccepted(enrollmentTicket().with("ticket_id", 23L).with("requester_user_id", "adm01"));
            assertAccepted(cancellationTicket().with("ticket_id", 24L).with("requester_user_id", "prof01"));
        }

        // ----- CHECK 3: PROCESSING 소유·리스·슬롯 ---------------------------------------------------
        @Test
        @DisplayName("CHECK 3: 올바른 PROCESSING 티켓은 수락(세대 0, 세대 5 모두)")
        void CHECK3_PROCESSING_수락() throws SQLException {
            assertAccepted(processing(enrollmentTicket()));
            assertAccepted(processing(loginTicket()).with("generation_no", 5).with("slot_claim_ref", 5));
        }

        @ParameterizedTest(name = "PROCESSING에서 {0}가 없으면 거절")
        @ValueSource(strings = {"owner_worker_ref", "lease_info", "slot_claim_ref"})
        void CHECK3_PROCESSING_누락(String column) throws SQLException {
            assertRejected(CHECK, processing(enrollmentTicket()).with(column, null));
        }

        @Test
        @DisplayName("CHECK 3: PROCESSING에서 owner·lease·slot이 모두 없으면 거절")
        void CHECK3_PROCESSING_전부_없음() throws SQLException {
            assertRejected(CHECK, processing(enrollmentTicket()).with("owner_worker_ref", null)
                .with("lease_info", null).with("slot_claim_ref", null));
        }

        @Test
        @DisplayName("CHECK 3: slot_claim_ref가 generation_no와 다르면 거절(양방향)")
        void CHECK3_슬롯_세대_불일치() throws SQLException {
            assertRejected(CHECK, processing(enrollmentTicket()).with("slot_claim_ref", 1));
            assertRejected(CHECK, processing(enrollmentTicket()).with("generation_no", 1));
            assertRejected(CHECK, processing(enrollmentTicket()).with("generation_no", 3).with("slot_claim_ref", 2));
        }

        static Stream<Arguments> 비PROCESSING_상태_x_PROCESSING_컬럼() {
            return cross(List.of("RECEIVED", "WAITING", "SUCCEEDED", "REJECTED"),
                List.of("owner_worker_ref", "lease_info", "slot_claim_ref"));
        }

        @ParameterizedTest(name = "{0}에 {1}가 있으면 거절")
        @MethodSource("com.crs.backend.db.SchemaConstraintIntegrationTest$Tickets#비PROCESSING_상태_x_PROCESSING_컬럼")
        void CHECK3_비PROCESSING_존재(String status, String column) throws SQLException {
            Row base = switch (status) {
                case "SUCCEEDED" -> succeeded(enrollmentTicket());
                case "REJECTED" -> rejected(enrollmentTicket(), "E04");
                default -> enrollmentTicket().with("status_cd", status);
            };
            Object value = processing(enrollmentTicket()).values().get(column);
            assertRejected(CHECK, base.with(column, value));
        }

        // ----- CHECK 4: 종결 결과·완료 시각 ------------------------------------------------------
        @Test
        @DisplayName("CHECK 4: SUCCEEDED인데 result_cd가 OK가 아니면 거절")
        void CHECK4_SUCCEEDED_비OK() throws SQLException {
            // E04는 ENROLLMENT의 허용 값이라 CHECK 5가 아니라 CHECK 4만 걸린다
            assertRejected(CHECK, succeeded(enrollmentTicket()).with("result_cd", "E04"));
        }

        @Test
        @DisplayName("CHECK 4: SUCCEEDED인데 processing_finished_at이 없으면 거절")
        void CHECK4_SUCCEEDED_완료시각_없음() throws SQLException {
            assertRejected(CHECK, succeeded(enrollmentTicket()).with("processing_finished_at", null));
        }

        @Test
        @DisplayName("CHECK 4: SUCCEEDED인데 result_cd가 없으면 거절")
        void CHECK4_SUCCEEDED_결과_없음() throws SQLException {
            assertRejected(CHECK, succeeded(enrollmentTicket()).with("result_cd", null));
        }

        @Test
        @DisplayName("CHECK 4: REJECTED인데 result_cd가 OK이면 거절")
        void CHECK4_REJECTED_OK() throws SQLException {
            assertRejected(CHECK, rejected(enrollmentTicket(), "OK"));
        }

        @Test
        @DisplayName("CHECK 4: REJECTED인데 result_cd가 없으면 거절")
        void CHECK4_REJECTED_결과_없음() throws SQLException {
            assertRejected(CHECK, rejected(enrollmentTicket(), "E04").with("result_cd", null));
        }

        @Test
        @DisplayName("CHECK 4: REJECTED인데 processing_finished_at이 없으면 거절")
        void CHECK4_REJECTED_완료시각_없음() throws SQLException {
            assertRejected(CHECK, rejected(enrollmentTicket(), "E04").with("processing_finished_at", null));
        }

        @ParameterizedTest(name = "{0}에 result_cd가 있으면 거절")
        @ValueSource(strings = {"RECEIVED", "WAITING"})
        void CHECK4_진행중_결과_존재(String status) throws SQLException {
            assertRejected(CHECK, enrollmentTicket().with("status_cd", status).with("result_cd", "OK"));
            assertRejected(CHECK, enrollmentTicket().with("status_cd", status).with("result_cd", "E04"));
        }

        @ParameterizedTest(name = "{0}에 processing_finished_at이 있으면 거절")
        @ValueSource(strings = {"RECEIVED", "WAITING"})
        void CHECK4_진행중_완료시각_존재(String status) throws SQLException {
            assertRejected(CHECK, enrollmentTicket().with("status_cd", status).with("processing_finished_at", TS));
        }

        @Test
        @DisplayName("CHECK 4: PROCESSING에 result_cd 또는 processing_finished_at이 있으면 거절")
        void CHECK4_PROCESSING_결과_완료시각() throws SQLException {
            assertRejected(CHECK, processing(enrollmentTicket()).with("result_cd", "OK"));
            assertRejected(CHECK, processing(enrollmentTicket()).with("processing_finished_at", TS));
        }

        // ----- CHECK 5: 유형별 허용 결과 코드 -----------------------------------------------------------
        static final Map<String, List<String>> ALLOWED = Map.of(
            "LOGIN", List.of("OK", "E01", "E99"),
            "ADMISSION", List.of("OK", "E02", "E99"),
            "ENROLLMENT", List.of("OK", "E02", "E04", "E05", "E06", "E07", "E15", "E99"),
            "CANCELLATION", List.of("OK", "E02", "E04", "E05", "E08", "E15", "E99"));

        static final List<String> TYPES = List.of("LOGIN", "ADMISSION", "ENROLLMENT", "CANCELLATION");

        static Stream<Arguments> 허용_결과_코드() {
            return TYPES.stream().flatMap(type -> ALLOWED.get(type).stream().map(code -> Arguments.of(type, code)));
        }

        /** 3.4가 적은 허용 목록에 없는 코드(E01~E15, E99, 형식 밖 값)를 유형마다 모두 만든다. */
        static Stream<Arguments> 허용_밖_결과_코드() {
            List<String> universe = new ArrayList<>();
            for (int i = 1; i <= 15; i++) {
                universe.add(String.format("E%02d", i));
            }
            universe.add("E99");
            universe.add("E00");
            universe.add("ok");
            universe.add("FAIL");
            return TYPES.stream().flatMap(type -> universe.stream()
                .filter(code -> !ALLOWED.get(type).contains(code))
                .map(code -> Arguments.of(type, code)));
        }

        @ParameterizedTest(name = "{0}에 허용된 {1}는 수락")
        @MethodSource("com.crs.backend.db.SchemaConstraintIntegrationTest$Tickets#허용_결과_코드")
        void CHECK5_허용_결과_수락(String type, String code) throws SQLException {
            Row ticket = code.equals("OK") ? succeeded(ticketOf(type)) : rejected(ticketOf(type), code);
            assertAccepted(ticket);
        }

        @ParameterizedTest(name = "{0}에 허용되지 않은 {1}는 거절")
        @MethodSource("com.crs.backend.db.SchemaConstraintIntegrationTest$Tickets#허용_밖_결과_코드")
        void CHECK5_허용_밖_결과_거절(String type, String code) throws SQLException {
            assertRejected(CHECK, rejected(ticketOf(type), code));
        }

        @Test
        @DisplayName("CHECK 5: 명시 사례 — LOGIN E07, ADMISSION E04, ENROLLMENT E08, CANCELLATION E07")
        void CHECK5_명시_사례() throws SQLException {
            assertRejected(CHECK, rejected(loginTicket(), "E07"));
            assertRejected(CHECK, rejected(admissionTicket(), "E04"));
            assertRejected(CHECK, rejected(enrollmentTicket(), "E08"));
            assertRejected(CHECK, rejected(cancellationTicket(), "E07"));
        }

        // ----- UNIQUE / 부분 유일 -----------------------------------------------------------------
        @Test
        @DisplayName("anon_proof_verifier 중복은 UNIQUE 위반")
        void 검증값_UNIQUE() throws SQLException {
            // 기준 행의 LOGIN 티켓 1이 hex64('a')를 쓴다. 컨텍스트는 다르다
            assertRejected(UNIQUE, loginTicket().with("anon_proof_verifier", hex64('a')));
        }

        @Test
        @DisplayName("anon_proof_verifier가 NULL인 비LOGIN 티켓은 여러 건 수락(UNIQUE는 NULL을 허용)")
        void 검증값_NULL_다건() throws SQLException {
            assertAccepted(enrollmentTicket().with("ticket_id", 30L).with("requester_user_id", "stu01"));
            assertAccepted(succeeded(enrollmentTicket()).with("ticket_id", 31L).with("requester_user_id", "stu01"));
            assertAccepted(succeeded(enrollmentTicket()).with("ticket_id", 32L).with("requester_user_id", "stu01"));
        }

        @ParameterizedTest(name = "첫 티켓 {0} 뒤 같은 요청자의 {1} 티켓은 거절")
        @CsvSource({"WAITING,WAITING", "WAITING,RECEIVED", "RECEIVED,WAITING", "RECEIVED,RECEIVED"})
        void 부분유일_요청자_미완결_둘(String first, String second) throws SQLException {
            assertAccepted(enrollmentTicket().with("status_cd", first));
            assertRejected(UNIQUE, enrollmentTicket().with("ticket_id", 12L).with("status_cd", second));
        }

        @Test
        @DisplayName("INV-5: PROCESSING 티켓이 있는 요청자의 새 미완결 티켓도 거절(반대 방향 포함)")
        void 부분유일_PROCESSING() throws SQLException {
            assertAccepted(processing(enrollmentTicket()));
            assertRejected(UNIQUE, enrollmentTicket().with("ticket_id", 12L));
            assertRejected(UNIQUE, enrollmentTicket().with("ticket_id", 13L).with("status_cd", "RECEIVED"));
        }

        @Test
        @DisplayName("INV-5: 유형이 달라도(ADMISSION·ENROLLMENT·CANCELLATION) 같은 요청자의 미완결은 하나")
        void 부분유일_유형_무관() throws SQLException {
            assertAccepted(admissionTicket());
            assertRejected(UNIQUE, enrollmentTicket().with("ticket_id", 12L));
            assertRejected(UNIQUE, cancellationTicket().with("ticket_id", 13L));
            assertRejected(UNIQUE, admissionTicket().with("ticket_id", 14L));
        }

        @Test
        @DisplayName("INV-5: 서로 다른 요청자의 미완결 티켓은 각각 수락")
        void 부분유일_다른_요청자_수락() throws SQLException {
            assertAccepted(enrollmentTicket());
            assertAccepted(enrollmentTicket().with("ticket_id", 12L).with("requester_user_id", "stu01"));
            assertAccepted(enrollmentTicket().with("ticket_id", 13L).with("requester_user_id", "adm01"));
        }

        @Test
        @DisplayName("INV-5: 이전 티켓이 SUCCEEDED 또는 REJECTED가 되면 같은 요청자의 새 미완결 티켓은 수락")
        void 부분유일_종결_뒤_수락() throws SQLException {
            // 기준 행에서 stu01의 티켓 2·3·4는 SUCCEEDED
            assertAccepted(enrollmentTicket().with("requester_user_id", "stu01"));
            assertAccepted(rejected(enrollmentTicket(), "E04").with("ticket_id", 12L).with("requester_user_id", "stu02"));
            assertAccepted(enrollmentTicket().with("ticket_id", 13L).with("requester_user_id", "stu02"));
        }

        @Test
        @DisplayName("INV-5: 미완결 티켓을 UPDATE로 종결하면 그 뒤에 새 미완결 티켓 수락")
        void 부분유일_UPDATE_종결() throws SQLException {
            assertAccepted(enrollmentTicket());
            assertRejected(UNIQUE, enrollmentTicket().with("ticket_id", 12L));
            assertAccepted("""
                UPDATE tickets
                   SET status_cd = 'SUCCEEDED', result_cd = 'OK', processing_finished_at = ?
                 WHERE ticket_id = 10""", TS);
            assertAccepted(enrollmentTicket().with("ticket_id", 12L));
        }

        @ParameterizedTest(name = "첫 LOGIN {0} 뒤 같은 anon_context_id의 {1} LOGIN은 거절")
        @CsvSource({"WAITING,WAITING", "WAITING,RECEIVED", "RECEIVED,WAITING"})
        void 부분유일_LOGIN_컨텍스트(String first, String second) throws SQLException {
            assertAccepted(loginTicket().with("status_cd", first));
            assertRejected(UNIQUE, loginTicket().with("ticket_id", 12L).with("anon_proof_verifier", hex64('f'))
                .with("status_cd", second));
        }

        @Test
        @DisplayName("INV-5: LOGIN PROCESSING 뒤 같은 anon_context_id의 미완결 LOGIN은 거절")
        void 부분유일_LOGIN_PROCESSING() throws SQLException {
            assertAccepted(processing(loginTicket()));
            assertRejected(UNIQUE, loginTicket().with("ticket_id", 12L).with("anon_proof_verifier", hex64('f')));
        }

        @Test
        @DisplayName("INV-5: 다른 anon_context_id의 LOGIN, 종결된 같은 컨텍스트의 LOGIN은 수락")
        void 부분유일_LOGIN_수락() throws SQLException {
            assertAccepted(loginTicket());
            assertAccepted(loginTicket().with("ticket_id", 12L).with("anon_proof_verifier", hex64('f'))
                .with("anon_context_id", UUID.randomUUID()));
            // 기준 행의 LOGIN 티켓 1(CTX_A)은 SUCCEEDED
            assertAccepted(loginTicket().with("ticket_id", 13L).with("anon_proof_verifier", hex64('9'))
                .with("anon_context_id", CTX_A));
            assertAccepted(rejected(loginTicket(), "E01").with("ticket_id", 14L)
                .with("anon_proof_verifier", hex64('8')));
        }

        @Test
        @DisplayName("INV-5: LOGIN 부분 인덱스는 비LOGIN 티켓과 서로 간섭하지 않는다")
        void 부분유일_LOGIN과_비LOGIN_독립() throws SQLException {
            assertAccepted(loginTicket());
            assertAccepted(enrollmentTicket());
        }

        // ----- FK --------------------------------------------------------------------------------
        @Test
        @DisplayName("FK: requester_user_id가 users에 없으면 거절")
        void 요청자_FK() throws SQLException {
            assertRejected(FK, enrollmentTicket().with("requester_user_id", "nobody"));
        }

        @Test
        @DisplayName("FK: session_ref가 sessions에 없으면 거절")
        void 세션_FK() throws SQLException {
            assertRejected(FK, enrollmentTicket().with("session_ref", UUID.randomUUID()));
        }

        @Test
        @DisplayName("FK: target_course_inst_id가 courses에 없으면 거절")
        void 대상과목_FK() throws SQLException {
            assertRejected(FK, enrollmentTicket().with("target_course_inst_id", UUID.randomUUID()));
            assertRejected(FK, cancellationTicket().with("target_course_inst_id", UUID.randomUUID()));
        }

        @Test
        @DisplayName("course_code_snapshot은 과목 코드가 바뀌어도 되는 기록이라 과목의 코드와 달라도 수락")
        void 코드_기록_독립() throws SQLException {
            assertAccepted(enrollmentTicket().with("course_code_snapshot", "OLDCODE"));
        }
    }

    // =============================================================================================
    @Nested
    @DisplayName("service_settings (3.5)")
    class ServiceSettings {
        @Test
        @DisplayName("V1이 넣은 초기 행: singleton=true, N=100, 변경자·시각 NULL, 행은 1개")
        void 초기_행() throws SQLException {
            assertEquals(1L, scalar("SELECT count(*) FROM service_settings"));
            assertEquals(true, scalar("SELECT singleton FROM service_settings"));
            assertEquals(100, scalar("SELECT concurrency_limit FROM service_settings"));
            assertNull(scalar("SELECT last_changed_by FROM service_settings"));
            assertNull(scalar("SELECT last_changed_at FROM service_settings"));
        }

        @Test
        @DisplayName("singleton=false 행은 CHECK 위반")
        void 두번째_행_false() throws SQLException {
            assertRejected(CHECK, """
                INSERT INTO service_settings (singleton, concurrency_limit) VALUES (false, 100)""");
        }

        @Test
        @DisplayName("singleton=true 두 번째 행은 PK 위반")
        void 두번째_행_true() throws SQLException {
            assertRejected(UNIQUE, """
                INSERT INTO service_settings (singleton, concurrency_limit) VALUES (true, 100)""");
        }

        @Test
        @DisplayName("singleton 기본값이 true라 생략한 INSERT는 PK 위반")
        void singleton_기본값() throws SQLException {
            assertRejected(UNIQUE, "INSERT INTO service_settings (concurrency_limit) VALUES (100)");
        }

        @Test
        @DisplayName("UPDATE로 singleton을 false로 바꾸면 CHECK 위반")
        void singleton_UPDATE_false() throws SQLException {
            assertRejected(CHECK, "UPDATE service_settings SET singleton = false");
        }

        @Test
        @DisplayName("concurrency_limit NOT NULL")
        void 한도_NULL() throws SQLException {
            assertRejected(NOT_NULL, "UPDATE service_settings SET concurrency_limit = NULL");
            assertRejected(NOT_NULL, "UPDATE service_settings SET singleton = NULL");
        }

        @ParameterizedTest(name = "concurrency_limit = {0}")
        @ValueSource(ints = {0, -1, 1001, 2000})
        void 한도_범위_밖(int value) throws SQLException {
            assertRejected(CHECK, "UPDATE service_settings SET concurrency_limit = ?", value);
        }

        @ParameterizedTest(name = "concurrency_limit = {0}")
        @ValueSource(ints = {1, 2, 100, 999, 1000})
        void 한도_범위_안_수락(int value) throws SQLException {
            assertAccepted("UPDATE service_settings SET concurrency_limit = ?", value);
            assertEquals(value, scalar("SELECT concurrency_limit FROM service_settings"));
        }

        @Test
        @DisplayName("변경자만 있고 변경 시각이 없으면 거절")
        void 변경자만() throws SQLException {
            assertRejected(CHECK, "UPDATE service_settings SET last_changed_by = 'adm01'");
        }

        @Test
        @DisplayName("변경 시각만 있고 변경자가 없으면 거절")
        void 시각만() throws SQLException {
            assertRejected(CHECK, "UPDATE service_settings SET last_changed_at = ?", TS);
        }

        @Test
        @DisplayName("변경자와 시각이 모두 있거나 모두 없으면 수락")
        void 변경자_시각_짝_수락() throws SQLException {
            assertAccepted("UPDATE service_settings SET last_changed_by = 'adm01', last_changed_at = ?", TS);
            assertAccepted("UPDATE service_settings SET last_changed_by = NULL, last_changed_at = NULL");
        }

        @Test
        @DisplayName("FK: last_changed_by가 users에 없으면 거절")
        void 변경자_FK() throws SQLException {
            assertRejected(FK, "UPDATE service_settings SET last_changed_by = 'nobody', last_changed_at = ?", TS);
        }
    }

    // =============================================================================================
    @Nested
    @DisplayName("sessions (3.6)")
    class Sessions {
        @Test
        @DisplayName("유효한 세션은 수락(양성 대조)")
        void 유효_수락() throws SQLException {
            assertAccepted(sessionRow());
        }

        @Test
        @DisplayName("3.6의 INSERT 문(logged_in_at 매개변수 하나로 expires_at 계산)은 수락")
        void 계획_INSERT_문() throws SQLException {
            assertAccepted("""
                INSERT INTO sessions (session_id, user_id, role_cd, login_ticket_id, logged_in_at, expires_at)
                VALUES (?, ?, ?, ?,
                        CAST(? AS timestamptz),
                        CAST(? AS timestamptz) + interval '168 hours')""",
                UUID.randomUUID(), "stu02", "STUDENT", 6L, TS.toString(), TS.toString());
        }

        @Test
        @DisplayName("session_id 중복은 PK 위반")
        void PK_중복() throws SQLException {
            assertRejected(UNIQUE, sessionRow().with("session_id", S1));
        }

        @Test
        @DisplayName("FK: user_id가 users에 없으면 거절")
        void 사용자_FK() throws SQLException {
            assertRejected(FK, sessionRow().with("user_id", "nobody"));
        }

        @ParameterizedTest(name = "role_cd = {0}")
        @ValueSource(strings = {"GUEST", "student", ""})
        void 역할_집합_밖(String value) throws SQLException {
            assertRejected(CHECK, sessionRow().with("role_cd", value));
        }

        @Test
        @DisplayName("login_ticket_id가 이미 다른 세션이 쓰면 UNIQUE 위반(R11)")
        void 로그인_티켓_UNIQUE() throws SQLException {
            // 티켓 1은 기준 세션 S1이 쓴다
            assertRejected(UNIQUE, sessionRow().with("login_ticket_id", 1L));
        }

        @Test
        @DisplayName("FK: login_ticket_id가 tickets에 없으면 거절")
        void 로그인_티켓_FK() throws SQLException {
            assertRejected(FK, sessionRow().with("login_ticket_id", 9999L));
        }

        @ParameterizedTest(name = "expires_at = logged_in_at + {0}시간")
        @ValueSource(longs = {0L, 1L, 167L, 169L, -168L})
        void 만료_168시간이_아님(long hours) throws SQLException {
            assertRejected(CHECK, sessionRow().with("expires_at", TS.plusHours(hours)));
        }

        @Test
        @DisplayName("expires_at이 1마이크로초만 어긋나도 거절, 정확히 168시간이면 수락")
        void 만료_정밀도() throws SQLException {
            assertRejected(CHECK, sessionRow().with("expires_at", TS.plusHours(168).plusNanos(1_000)));
            assertAccepted(sessionRow().with("expires_at", TS.plusHours(168)));
        }

        @Test
        @DisplayName("UPDATE로 expires_at 또는 logged_in_at 한쪽만 바꾸면 거절")
        void 만료_UPDATE() throws SQLException {
            assertRejected(CHECK, "UPDATE sessions SET expires_at = expires_at + interval '1 hour' WHERE session_id = ?", S1);
            assertRejected(CHECK, "UPDATE sessions SET logged_in_at = logged_in_at + interval '1 hour' WHERE session_id = ?", S1);
        }

        @ParameterizedTest(name = "session_status = {0}")
        @ValueSource(strings = {"EXPIRED", "active", ""})
        void 상태_집합_밖(String value) throws SQLException {
            assertRejected(CHECK, sessionRow().with("session_status", value));
        }

        @ParameterizedTest(name = "revoke_reason = {0}")
        @ValueSource(strings = {"TIMEOUT", "logout", ""})
        void 폐기_사유_집합_밖(String value) throws SQLException {
            assertRejected(CHECK, sessionRow().with("session_status", "REVOKED").with("revoked_at", TS)
                .with("revoke_reason", value));
        }

        @ParameterizedTest(name = "REVOKED + {0}는 수락")
        @ValueSource(strings = {"LOGOUT", "REFRESH_REUSE"})
        void REVOKED_수락(String reason) throws SQLException {
            assertAccepted(sessionRow().with("session_status", "REVOKED").with("revoked_at", TS)
                .with("revoke_reason", reason));
        }

        @Test
        @DisplayName("REVOKED인데 revoked_at이 없으면 거절")
        void REVOKED_시각_없음() throws SQLException {
            assertRejected(CHECK, sessionRow().with("session_status", "REVOKED").with("revoke_reason", "LOGOUT"));
        }

        @Test
        @DisplayName("REVOKED인데 revoke_reason이 없으면 거절")
        void REVOKED_사유_없음() throws SQLException {
            assertRejected(CHECK, sessionRow().with("session_status", "REVOKED").with("revoked_at", TS));
        }

        @Test
        @DisplayName("REVOKED인데 시각·사유가 모두 없으면 거절")
        void REVOKED_둘다_없음() throws SQLException {
            assertRejected(CHECK, sessionRow().with("session_status", "REVOKED"));
        }

        @Test
        @DisplayName("ACTIVE인데 revoked_at이 있으면 거절")
        void ACTIVE_시각_있음() throws SQLException {
            assertRejected(CHECK, sessionRow().with("revoked_at", TS));
        }

        @Test
        @DisplayName("ACTIVE인데 revoke_reason이 있으면 거절")
        void ACTIVE_사유_있음() throws SQLException {
            assertRejected(CHECK, sessionRow().with("revoke_reason", "LOGOUT"));
        }

        @Test
        @DisplayName("ACTIVE인데 시각·사유가 모두 있으면 거절")
        void ACTIVE_둘다_있음() throws SQLException {
            assertRejected(CHECK, sessionRow().with("revoked_at", TS).with("revoke_reason", "REFRESH_REUSE"));
        }

        @Test
        @DisplayName("기본값: session_status=ACTIVE")
        void 상태_기본값() throws SQLException {
            Row row = sessionRow().without("session_status");
            assertAccepted(row);
            assertEquals("ACTIVE", scalar("SELECT session_status FROM sessions WHERE session_id = ?",
                row.values().get("session_id")));
        }

        @Test
        @DisplayName("PROFESSOR 세션에 entered_at이 있으면 거절")
        void 교수_진입_시각() throws SQLException {
            assertRejected(CHECK, sessionRow().with("user_id", "prof01").with("role_cd", "PROFESSOR")
                .with("entered_at", TS));
        }

        @Test
        @DisplayName("ADMIN 세션에 entered_at이 있으면 거절")
        void 관리자_진입_시각() throws SQLException {
            assertRejected(CHECK, sessionRow().with("user_id", "adm01").with("role_cd", "ADMIN")
                .with("entered_at", TS));
        }

        @Test
        @DisplayName("STUDENT 세션의 entered_at은 수락")
        void 학생_진입_시각_수락() throws SQLException {
            assertAccepted(sessionRow().with("entered_at", TS));
        }

        @Test
        @DisplayName("PROFESSOR 세션은 entered_at이 NULL이면 수락")
        void 교수_세션_수락() throws SQLException {
            assertAccepted(sessionRow().with("user_id", "prof01").with("role_cd", "PROFESSOR"));
        }

        @Test
        @DisplayName("ADMIN 세션은 entered_at이 NULL이면 수락")
        void 관리자_세션_수락() throws SQLException {
            assertAccepted(sessionRow().with("user_id", "adm01").with("role_cd", "ADMIN"));
        }
    }

    // =============================================================================================
    @Nested
    @DisplayName("refresh_tokens (3.7)")
    class RefreshTokens {
        @Test
        @DisplayName("refresh_token_hash 중복은 PK 위반")
        void PK_중복() throws SQLException {
            assertRejected(UNIQUE, refreshRow().with("refresh_token_hash", hex64('e')));
        }

        @Test
        @DisplayName("FK: session_id가 sessions에 없으면 거절")
        void 세션_FK() throws SQLException {
            assertRejected(FK, refreshRow().with("session_id", UUID.randomUUID()));
        }

        @Test
        @DisplayName("R12: 한 세션에 미소비 기록이 둘이면 부분 유일 위반")
        void 미소비_둘() throws SQLException {
            // 기준 행에 S1의 미소비 기록(hex64('e'))이 있다
            assertRejected(UNIQUE, refreshRow().with("consumed_at", null));
        }

        @Test
        @DisplayName("소비된 기록 하나와 미소비 기록 하나는 수락, 소비된 기록은 여러 건도 수락")
        void 소비와_미소비_수락() throws SQLException {
            assertAccepted(refreshRow());
            assertAccepted(refreshRow().with("refresh_token_hash", hex64('7')));
            assertAccepted(refreshRow().with("refresh_token_hash", hex64('6')));
        }

        @Test
        @DisplayName("R12: 다른 세션의 미소비 기록은 수락")
        void 다른_세션_미소비_수락() throws SQLException {
            Row second = sessionRow();
            assertAccepted(second);
            assertAccepted(refreshRow().with("session_id", second.values().get("session_id")).with("consumed_at", null));
        }

        @Test
        @DisplayName("회전: 소비 표시를 먼저 하면 같은 세션의 새 미소비 기록 INSERT는 수락, 순서가 반대면 거절")
        void 회전_순서() throws SQLException {
            assertRejected(UNIQUE, refreshRow().with("refresh_token_hash", hex64('7')).with("consumed_at", null));
            assertAccepted("UPDATE refresh_tokens SET consumed_at = ? WHERE refresh_token_hash = ?", TS, hex64('e'));
            assertAccepted(refreshRow().with("refresh_token_hash", hex64('7')).with("consumed_at", null));
        }

        @Test
        @DisplayName("UPDATE로 소비된 기록을 미소비로 되돌려 미소비가 둘이 되면 거절")
        void 소비_되돌리기() throws SQLException {
            assertAccepted(refreshRow());
            assertRejected(UNIQUE, "UPDATE refresh_tokens SET consumed_at = NULL WHERE refresh_token_hash = ?", hex64('d'));
        }

        @Test
        @DisplayName("refresh_token_hash 65자는 거절(char(64))")
        void 길이_한도() throws SQLException {
            assertRejected(TOO_LONG, refreshRow().with("refresh_token_hash", "f".repeat(65)));
        }
    }

    // =============================================================================================
    @Nested
    @DisplayName("INV-8 / 3.9 — FK는 모두 NO ACTION")
    class NoActionForeignKeys {
        @Test
        @DisplayName("수강·티켓이 참조하는 과목 DELETE는 거절")
        void 과목_DELETE() throws SQLException {
            assertRejected(FK, "DELETE FROM courses WHERE course_inst_id = ?", C1);
            assertRejected(FK, "DELETE FROM courses WHERE course_inst_id = ?", C2);
        }

        @Test
        @DisplayName("담당 교수(users) DELETE는 과목이 참조하므로 거절")
        void 담당교수_삭제() throws SQLException {
            assertRejected(FK, "DELETE FROM users WHERE user_id = 'prof01'");
        }

        @ParameterizedTest(name = "users.{0} DELETE는 거절")
        @ValueSource(strings = {"stu01", "stu02", "adm01"})
        void 사용자_DELETE(String userId) throws SQLException {
            assertRejected(FK, "DELETE FROM users WHERE user_id = ?", userId);
        }

        @Test
        @DisplayName("세션 DELETE는 티켓·Refresh 기록이 참조하므로 거절")
        void 세션_DELETE() throws SQLException {
            assertRejected(FK, "DELETE FROM sessions WHERE session_id = ?", S1);
        }

        @Test
        @DisplayName("세션이 참조하는 LOGIN 티켓 DELETE는 거절")
        void 로그인_티켓_DELETE() throws SQLException {
            assertRejected(FK, "DELETE FROM tickets WHERE ticket_id = 1");
        }

        @Test
        @DisplayName("수강 행의 근거 티켓 DELETE는 거절")
        void 근거_티켓_DELETE() throws SQLException {
            assertRejected(FK, "DELETE FROM tickets WHERE ticket_id = 2");
        }

        @Test
        @DisplayName("참조되는 키를 UPDATE로 바꾸는 것도 거절(ON UPDATE도 NO ACTION)")
        void 참조_키_UPDATE() throws SQLException {
            assertRejected(FK, "UPDATE users SET user_id = 'renamed' WHERE user_id = 'stu01'");
            assertRejected(FK, "UPDATE courses SET course_inst_id = gen_random_uuid() WHERE course_inst_id = ?", C1);
        }

        @Test
        @DisplayName("참조하는 행이 없는 행은 DELETE 가능(대조)")
        void 참조_없는_DELETE_수락() throws SQLException {
            assertAccepted(userRow("lonely", "STUDENT"));
            assertAccepted("DELETE FROM users WHERE user_id = 'lonely'");
            assertAccepted("DELETE FROM tickets WHERE ticket_id = 4");
            assertAccepted("DELETE FROM refresh_tokens WHERE refresh_token_hash = ?", hex64('e'));
        }
    }

    // =============================================================================================
    // 3.1~3.7의 모든 NOT NULL 컬럼. 그 외에는 유효한 행에서 이 컬럼만 명시적 NULL로 바꾼다.
    // 기본값이 있는 컬럼(course_inst_id, active_cnt, course_status, enroll_status, status_cd, retry_count,
    // generation_no, session_status)도 명시적 NULL은 거절돼야 한다.
    // service_settings는 INSERT로 두 번째 행을 만들 수 없어 UPDATE로 본다(ServiceSettings 중첩 클래스).
    // =============================================================================================
    static Row notNullSample(String table) {
        return switch (table) {
            case "users" -> userRow("u-new", "STUDENT");
            case "courses" -> courseRow();
            case "enrollments" -> enrRow();
            case "tickets" -> enrollmentTicket();
            case "sessions" -> sessionRow();
            case "refresh_tokens" -> refreshRow();
            default -> throw new IllegalArgumentException(table);
        };
    }

    static Stream<Arguments> notNullColumns() {
        Map<String, List<String>> columns = new LinkedHashMap<>();
        columns.put("users", List.of("user_id", "password_hash", "user_name", "role_cd"));
        columns.put("courses", List.of("course_inst_id", "course_code", "professor_id", "course_title",
            "course_category", "grading_type", "max_capacity", "min_enrollment", "active_cnt", "classroom",
            "course_status", "course_created_at"));
        columns.put("enrollments", List.of("student_id", "course_inst_id", "enroll_status", "enroll_type",
            "enrolled_at"));
        columns.put("tickets", List.of("ticket_id", "request_type", "ticket_issued_at", "status_cd",
            "retry_count", "generation_no"));
        columns.put("sessions", List.of("session_id", "user_id", "role_cd", "login_ticket_id", "logged_in_at",
            "expires_at", "session_status"));
        columns.put("refresh_tokens", List.of("refresh_token_hash", "session_id", "issued_at"));
        return columns.entrySet().stream()
            .flatMap(e -> e.getValue().stream().map(column -> Arguments.of(e.getKey(), column)));
    }

    @Nested
    @DisplayName("NOT NULL 전수 (3.1~3.7)")
    class NotNullColumns {
        @ParameterizedTest(name = "{0}.{1} = NULL")
        @MethodSource("com.crs.backend.db.SchemaConstraintIntegrationTest#notNullColumns")
        void 명시적_NULL_거절(String table, String column) throws SQLException {
            assertRejected(NOT_NULL, notNullSample(table).with(column, null));
        }

        @ParameterizedTest(name = "{0}.{1}은 NULL 허용")
        @CsvSource({
            "courses,course_updated_at",
            "enrollments,assigned_by_user_id",
            "tickets,processing_started_at",
            "sessions,revoked_at",
            "sessions,first_token_issued_at",
            "sessions,entered_at",
            "refresh_tokens,consumed_at"})
        void NULL_허용_컬럼_수락(String table, String column) throws SQLException {
            // 3.2~3.7 표에서 NULL로 적힌 컬럼. 기준 샘플 행에서 해당 컬럼을 NULL로 둬도 수락돼야 한다
            if (table.equals("refresh_tokens")) {
                // R12: 기준 행이 S1의 미소비 기록을 이미 갖고 있으므로 먼저 소비해 부분 유일 인덱스와 겹치지 않게 한다
                assertAccepted("UPDATE refresh_tokens SET consumed_at = ? WHERE refresh_token_hash = ?", TS, hex64('e'));
            }
            assertAccepted(notNullSample(table).with(column, null));
        }
    }
}
