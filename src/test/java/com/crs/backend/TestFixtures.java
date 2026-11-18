package com.crs.backend;

import com.crs.backend.auth.AuthService;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 통합 테스트용 데이터 준비 도우미.
 * V1 스키마의 FK·CHECK를 지키면서 세션과 티켓을 만든다(구현계획 3절).
 *
 * - 세션은 근거 LOGIN 티켓(R11)이 있어야 하고, 만료 시각은 로그인 + 168시간이어야 한다.
 * - 학생 신청 내역(enroll_type STUDENT)은 같은 학생·같은 과목의 근거 티켓이 있어야 한다(R05, 복합 FK).
 * - 인증 후 티켓은 요청자와 세션이 있어야 하고, 사용자당 미완료 티켓은 1건이다(INV-5).
 */
public final class TestFixtures {

    private TestFixtures() {
    }

    /** 로그인이 끝난 세션 1개를 만들고 세션 ID를 돌려준다(근거 LOGIN 티켓 포함) */
    public static String session(JdbcClient jdbc, String userId, String role) {
        long loginTicketId = jdbc.sql("""
                        INSERT INTO tickets (request_type, anon_context_id, anon_proof_verifier,
                                             credential_expires_at, proof_expires_at, ticket_issued_at,
                                             status_cd, result_cd, processing_finished_at)
                        VALUES ('LOGIN', :context, :verifier,
                                now() + interval '5 minutes', now() + interval '10 minutes', now(),
                                'SUCCEEDED', 'OK', now())
                        RETURNING ticket_id""")
                .param("context", UUID.randomUUID().toString())
                .param("verifier", AuthService.hash(UUID.randomUUID().toString()))
                .query(Long.class).single();
        String sessionId = UUID.randomUUID().toString();
        jdbc.sql("""
                        INSERT INTO sessions (session_id, user_id, role_cd, login_ticket_id, logged_in_at, expires_at)
                        VALUES (:sessionId, :userId, :role, :loginTicketId, now(), now() + interval '168 hours')""")
                .param("sessionId", sessionId).param("userId", userId).param("role", role)
                .param("loginTicketId", loginTicketId)
                .update();
        return sessionId;
    }

    /** 대기 중(WAITING)인 학생 신청 티켓. EnrollmentService.enrollStudent의 근거 티켓으로 쓴다 */
    public static long waitingEnrollmentTicket(JdbcClient jdbc, String studentId, String sessionId,
                                               String courseInstId, String courseCode) {
        return jdbc.sql("""
                        INSERT INTO tickets (request_type, requester_user_id, session_ref, target_course_inst_id,
                                             course_code_snapshot, ticket_issued_at, status_cd)
                        VALUES ('ENROLLMENT', :studentId, :sessionId, :courseInstId, :courseCode, now(), 'WAITING')
                        RETURNING ticket_id""")
                .param("studentId", studentId).param("sessionId", sessionId)
                .param("courseInstId", courseInstId).param("courseCode", courseCode)
                .query(Long.class).single();
    }

    /** 이미 성공(SUCCEEDED/OK)으로 끝난 학생 신청 티켓. 학생 신청 내역을 직접 넣을 때 근거로 쓴다 */
    public static long succeededEnrollmentTicket(JdbcClient jdbc, String studentId, String sessionId,
                                                 String courseInstId, String courseCode) {
        return jdbc.sql("""
                        INSERT INTO tickets (request_type, requester_user_id, session_ref, target_course_inst_id,
                                             course_code_snapshot, ticket_issued_at, status_cd, result_cd,
                                             processing_finished_at)
                        VALUES ('ENROLLMENT', :studentId, :sessionId, :courseInstId, :courseCode, now(),
                                'SUCCEEDED', 'OK', now())
                        RETURNING ticket_id""")
                .param("studentId", studentId).param("sessionId", sessionId)
                .param("courseInstId", courseInstId).param("courseCode", courseCode)
                .query(Long.class).single();
    }

    /**
     * 테스트 과목(과목코드 접두어)과 그 과목을 가리키는 내역·티켓을 지운다.
     * FK 때문에 내역 → 티켓 → 과목 순서로 지운다.
     */
    public static void deleteCourses(JdbcClient jdbc, String codePrefix) {
        String courses = "SELECT course_inst_id FROM courses WHERE course_code LIKE :prefix";
        jdbc.sql("DELETE FROM enrollments WHERE course_inst_id IN (" + courses + ")")
                .param("prefix", codePrefix + "%").update();
        jdbc.sql("DELETE FROM tickets WHERE target_course_inst_id IN (" + courses + ")")
                .param("prefix", codePrefix + "%").update();
        jdbc.sql("DELETE FROM courses WHERE course_code LIKE :prefix")
                .param("prefix", codePrefix + "%").update();
    }
}
