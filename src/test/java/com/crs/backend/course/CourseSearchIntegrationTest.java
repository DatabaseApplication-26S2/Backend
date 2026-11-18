package com.crs.backend.course;

import static org.hamcrest.Matchers.contains;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.crs.backend.TestFixtures;
import com.crs.backend.TestcontainersConfiguration;
import com.crs.backend.adapter.out.persistence.jpa.UserAccountRepository;
import com.crs.backend.auth.AuthService;
import com.crs.backend.domain.entity.UserAccount;
import com.crs.backend.domain.enums.UserRole;
import com.crs.backend.domain.service.ConcurrencyTestConfig;
import com.crs.backend.queue.QueueService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RSetCache;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * BE-07 GET /api/v1/courses 계약 확인(openapi.yaml searchCourses).
 * 실제 PostgreSQL(Testcontainers)에 테스트 데이터를 넣고, 실제 로그인 토큰으로 호출한다.
 * 다른 테스트의 데이터와 섞이지 않게 테스트 과목코드는 모두 QZ로 시작하고 검색어 q=QZ로 조회한다.
 */
@SpringBootTest(properties = {
        "queue.scheduler-fixed-delay-ms=3600000",
        "spring.autoconfigure.exclude=org.redisson.spring.starter.RedissonAutoConfigurationV2,org.redisson.spring.starter.RedissonAutoConfigurationV4"
})
@Import({ConcurrencyTestConfig.class, TestcontainersConfiguration.class})
@AutoConfigureMockMvc
class CourseSearchIntegrationTest {

    private static final String PASSWORD = "Test-password1!";
    private static final String PROFESSOR_KIM = "qzprof1";
    private static final String PROFESSOR_PARK = "qzprof2";
    private static final String STUDENT = "qzstudent1";
    private static final String ADMIN = "qzadmin1";

    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired QueueService queue;
    @Autowired RedissonClient redis;
    @Autowired UserAccountRepository users;
    @Autowired JdbcClient jdbc;

    private RSetCache<String> activeUsers;

    @BeforeEach
    void seed() {
        activeUsers = redis.getSetCache("active_users");
        when(activeUsers.contains(anyString())).thenReturn(false); // 기본: 아무도 화면에 입장하지 않음

        // 이전 실행의 테스트 데이터 정리
        TestFixtures.deleteCourses(jdbc, "QZ"); // 내역 → 티켓 → 과목 순서(FK)

        user(PROFESSOR_KIM, "김민수", UserRole.PROFESSOR);
        user(PROFESSOR_PARK, "박서연", UserRole.PROFESSOR);
        user(STUDENT, "테스트 학생", UserRole.STUDENT);
        user(ADMIN, "관리자", UserRole.ADMIN); // 관리자 배정 내역의 배정자(FK)

        // 일부러 코드 순서를 섞어서 넣는다(응답은 과목코드 오름차순이어야 함)
        String db = course("QZ300", PROFESSOR_KIM, "QZ 데이터베이스", "MAJOR", "RELATIVE", 40, 10, 38, "OPEN");
        course("QZ100", PROFESSOR_PARK, "QZ 글쓰기와 소통", "GENERAL", "PASS_FAIL", 30, 5, 31, "OPEN"); // 정원 초과
        course("QZ200", PROFESSOR_KIM, "QZ 운영체제", "MAJOR", "ABSOLUTE", 35, 8, 0, "CLOSED");     // 폐강
        course("QZ400", PROFESSOR_PARK, "QZ 50%할인 특강", "GENERAL", "ABSOLUTE", 20, 1, 0, "OPEN");  // % 글자 포함

        // 학생은 QZ300을 신청한 상태
        jdbc.sql("""
                INSERT INTO enrollments (student_id, course_inst_id, enroll_status, enroll_type, enrolled_at, assigned_by_user_id)
                VALUES (:s, :c, 'ENROLLED', 'ADMIN', :t, :a)""")
                .param("s", STUDENT).param("c", db).param("t", Timestamp.from(Instant.now())).param("a", ADMIN)
                .update();
    }

    @AfterEach
    void resetRedisStub() {
        when(activeUsers.contains(anyString())).thenReturn(false);
    }

    // ---------------- 성공 ----------------

    @Test
    void 교수_검색_코드순_정렬_enrolled_없음_폐강포함() throws Exception {
        mvc.perform(get("/api/v1/courses").param("q", "QZ").header("Authorization", bearer(PROFESSOR_KIM)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.pageSize").value(20))
                .andExpect(jsonPath("$.totalItems").value(4))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.items[*].courseCode").value(contains("QZ100", "QZ200", "QZ300", "QZ400")))
                // 필드 이름과 값(QZ300)
                .andExpect(jsonPath("$.items[2].name").value("QZ 데이터베이스"))
                .andExpect(jsonPath("$.items[2].category").value("MAJOR"))
                .andExpect(jsonPath("$.items[2].professorName").value("김민수"))
                .andExpect(jsonPath("$.items[2].evaluationType").value("RELATIVE"))
                .andExpect(jsonPath("$.items[2].capacity").value(40))
                .andExpect(jsonPath("$.items[2].minimumEnrollment").value(10))
                .andExpect(jsonPath("$.items[2].activeEnrollmentCount").value(38))
                .andExpect(jsonPath("$.items[2].remainingSeats").value(2))
                .andExpect(jsonPath("$.items[2].classroom").value("공학관 301"))
                .andExpect(jsonPath("$.items[2].status").value("OPEN"))
                // 교수 응답에는 enrolled 필드 자체가 없어야 한다
                .andExpect(jsonPath("$.items[2].enrolled").doesNotExist())
                // 정원 초과 과목의 여석은 0(음수 아님), 폐강 과목도 포함
                .andExpect(jsonPath("$.items[0].remainingSeats").value(0))
                .andExpect(jsonPath("$.items[1].status").value("CLOSED"))
                // 내부 ID와 학점은 노출하지 않는다
                .andExpect(jsonPath("$.items[0].courseInstId").doesNotExist())
                .andExpect(jsonPath("$.items[0].credit").doesNotExist());
    }

    @Test
    void 입장한_학생은_enrolled를_받는다() throws Exception {
        when(activeUsers.contains(STUDENT)).thenReturn(true);
        mvc.perform(get("/api/v1/courses").param("q", "QZ").header("Authorization", bearer(STUDENT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].courseCode").value(contains("QZ100", "QZ200", "QZ300", "QZ400")))
                .andExpect(jsonPath("$.items[*].enrolled").value(contains(false, false, true, false)));
    }

    @Test
    void 이수구분_필터() throws Exception {
        mvc.perform(get("/api/v1/courses").param("q", "QZ").param("category", "MAJOR")
                        .header("Authorization", bearer(PROFESSOR_KIM)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.items[*].courseCode").value(contains("QZ200", "QZ300")));
    }

    @Test
    void 검색어는_대소문자를_무시하고_퍼센트를_글자_그대로_찾는다() throws Exception {
        // 소문자 qz도 일치
        mvc.perform(get("/api/v1/courses").param("q", "qz").header("Authorization", bearer(PROFESSOR_KIM)))
                .andExpect(jsonPath("$.totalItems").value(4));
        // LIKE였다면 %가 와일드카드라 모든 과목이 나온다. 글자 그대로 찾으면 제목에 %가 있는 1건뿐
        mvc.perform(get("/api/v1/courses").param("q", "%").header("Authorization", bearer(PROFESSOR_KIM)))
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].courseCode").value("QZ400"));
        // 교수 이름으로도 찾는다
        mvc.perform(get("/api/v1/courses").param("q", "박서연").header("Authorization", bearer(PROFESSOR_KIM)))
                .andExpect(jsonPath("$.items[*].courseCode").value(contains("QZ100", "QZ400")));
    }

    @Test
    void 페이지_20건_고정과_마지막_페이지_뒤() throws Exception {
        for (int i = 1; i <= 21; i++) { // 기존 4건 + 21건 = 25건
            course(String.format("QZP%02d", i), PROFESSOR_KIM, "QZ 페이지 과목", "GENERAL", "ABSOLUTE", 10, 1, 0, "OPEN");
        }
        mvc.perform(get("/api/v1/courses").param("q", "QZ").header("Authorization", bearer(PROFESSOR_KIM)))
                .andExpect(jsonPath("$.items.length()").value(20))
                .andExpect(jsonPath("$.totalItems").value(25))
                .andExpect(jsonPath("$.totalPages").value(2));
        mvc.perform(get("/api/v1/courses").param("q", "QZ").param("page", "2").header("Authorization", bearer(PROFESSOR_KIM)))
                .andExpect(jsonPath("$.items.length()").value(5))
                .andExpect(jsonPath("$.page").value(2));
        // 마지막 페이지 뒤: 빈 items와 정확한 합계
        mvc.perform(get("/api/v1/courses").param("q", "QZ").param("page", "5").header("Authorization", bearer(PROFESSOR_KIM)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.page").value(5))
                .andExpect(jsonPath("$.totalItems").value(25))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    void 결과_0건이면_totalPages_0() throws Exception {
        mvc.perform(get("/api/v1/courses").param("q", "QZ없는과목").header("Authorization", bearer(PROFESSOR_KIM)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(0))
                .andExpect(jsonPath("$.totalPages").value(0));
    }

    // ---------------- 오류 ----------------

    @Test
    void 토큰_없으면_401() throws Exception {
        mvc.perform(get("/api/v1/courses"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("E02"));
    }

    @Test
    void 입장하지_않은_학생은_409_E15() throws Exception {
        mvc.perform(get("/api/v1/courses").header("Authorization", bearer(STUDENT)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("E15"));
    }

    @Test
    void 잘못된_page와_category는_400_E09() throws Exception {
        mvc.perform(get("/api/v1/courses").param("page", "0").header("Authorization", bearer(PROFESSOR_KIM)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("E09"))
                .andExpect(jsonPath("$.violations[0].source").value("QUERY"))
                .andExpect(jsonPath("$.violations[0].field").value("page"));
        mvc.perform(get("/api/v1/courses").param("page", "abc").header("Authorization", bearer(PROFESSOR_KIM)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/courses").param("category", "FOO").header("Authorization", bearer(PROFESSOR_KIM)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations[0].field").value("category"));
    }

    // ---------------- GET /professor/courses ----------------

    @Test
    void 교수는_자기_담당_과목만_폐강포함_코드순으로_받는다() throws Exception {
        mvc.perform(get("/api/v1/professor/courses").header("Authorization", bearer(PROFESSOR_KIM)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                // 김민수 교수 과목만(박서연 교수의 QZ100·QZ400은 없음), 폐강 QZ200 포함
                .andExpect(jsonPath("$.items[*].courseCode").value(contains("QZ200", "QZ300")))
                .andExpect(jsonPath("$.items[0].status").value("CLOSED"))
                .andExpect(jsonPath("$.items[1].professorName").value("김민수"))
                .andExpect(jsonPath("$.items[1].remainingSeats").value(2))
                .andExpect(jsonPath("$.items[1].enrolled").doesNotExist())
                // 페이지 정보 없이 items만
                .andExpect(jsonPath("$.page").doesNotExist())
                .andExpect(jsonPath("$.totalItems").doesNotExist());
    }

    @Test
    void 교수가_아니면_403_E02_FORBIDDEN() throws Exception {
        when(activeUsers.contains(STUDENT)).thenReturn(true);
        mvc.perform(get("/api/v1/professor/courses").header("Authorization", bearer(STUDENT)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("E02"))
                .andExpect(jsonPath("$.reason").value("FORBIDDEN"));
    }

    @Test
    void 교수_담당과목_토큰_없으면_401() throws Exception {
        mvc.perform(get("/api/v1/professor/courses"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("E02"));
    }

    // ---------------- GET /me/enrollments ----------------

    @Test
    void 학생은_현재_신청_과목만_코드순으로_받는다() throws Exception {
        // seed: QZ300(ADMIN 배정, ENROLLED). 여기에 학생 신청 1건과 폐강으로 CLOSED된 1건을 더한다
        enrollment(STUDENT, "QZ100", "ENROLLED", "STUDENT");
        enrollment(STUDENT, "QZ200", "CLOSED", "STUDENT");
        when(activeUsers.contains(STUDENT)).thenReturn(true);

        mvc.perform(get("/api/v1/me/enrollments").header("Authorization", bearer(STUDENT)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.items[*].courseCode").value(contains("QZ100", "QZ300"))) // CLOSED QZ200 제외
                .andExpect(jsonPath("$.items[0].enrollmentType").value("STUDENT"))
                .andExpect(jsonPath("$.items[1].enrollmentType").value("ADMIN"))
                .andExpect(jsonPath("$.items[1].name").value("QZ 데이터베이스"))
                .andExpect(jsonPath("$.items[1].category").value("MAJOR"))
                .andExpect(jsonPath("$.items[1].professorName").value("김민수"))
                .andExpect(jsonPath("$.items[1].classroom").value("공학관 301"))
                .andExpect(jsonPath("$.items[1].status").value("ENROLLED"))
                .andExpect(jsonPath("$.items[1].enrolledAt").value(org.hamcrest.Matchers.endsWith("Z")))
                // 계약에 없는 필드는 내보내지 않는다
                .andExpect(jsonPath("$.items[1].capacity").doesNotExist())
                .andExpect(jsonPath("$.items[1].studentId").doesNotExist());
    }

    @Test
    void 내신청_입장하지_않은_학생은_409_E15() throws Exception {
        mvc.perform(get("/api/v1/me/enrollments").header("Authorization", bearer(STUDENT)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("E15"));
    }

    @Test
    void 내신청_학생이_아니면_403_E02() throws Exception {
        mvc.perform(get("/api/v1/me/enrollments").header("Authorization", bearer(PROFESSOR_KIM)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("E02"))
                .andExpect(jsonPath("$.reason").value("FORBIDDEN"));
    }

    @Test
    void 내신청_토큰_없으면_401() throws Exception {
        mvc.perform(get("/api/v1/me/enrollments"))
                .andExpect(status().isUnauthorized());
    }

    // ---------------- GET /courses/{courseCode} (BE-12) ----------------

    @Test
    void 과목_상세_교수는_enrolled_없이_받는다() throws Exception {
        mvc.perform(get("/api/v1/courses/QZ300").header("Authorization", bearer(PROFESSOR_KIM)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.courseCode").value("QZ300"))
                .andExpect(jsonPath("$.name").value("QZ 데이터베이스"))
                .andExpect(jsonPath("$.professorName").value("김민수"))
                .andExpect(jsonPath("$.capacity").value(40))
                .andExpect(jsonPath("$.activeEnrollmentCount").value(38))
                .andExpect(jsonPath("$.remainingSeats").value(2))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.enrolled").doesNotExist())
                .andExpect(jsonPath("$.courseInstId").doesNotExist());
    }

    @Test
    void 과목_상세_입장한_학생은_enrolled를_받는다() throws Exception {
        when(activeUsers.contains(STUDENT)).thenReturn(true);
        String token = bearer(STUDENT);
        mvc.perform(get("/api/v1/courses/QZ300").header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enrolled").value(true));
        mvc.perform(get("/api/v1/courses/QZ100").header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enrolled").value(false));
    }

    @Test
    void 폐강_과목도_상세는_200() throws Exception {
        mvc.perform(get("/api/v1/courses/QZ200").header("Authorization", bearer(PROFESSOR_KIM)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.activeEnrollmentCount").value(0));
    }

    @Test
    void 과목_상세_없으면_404_E04_형식_오류는_400_E09() throws Exception {
        String token = bearer(PROFESSOR_KIM);
        mvc.perform(get("/api/v1/courses/NOPE999").header("Authorization", token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("E04"));
        mvc.perform(get("/api/v1/courses/BAD_CODE").header("Authorization", token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("E09"))
                .andExpect(jsonPath("$.violations[0].source").value("PATH"))
                .andExpect(jsonPath("$.violations[0].field").value("courseCode"));
        // 과목코드는 대소문자를 구분한다
        mvc.perform(get("/api/v1/courses/qz300").header("Authorization", token))
                .andExpect(status().isNotFound());
    }

    @Test
    void 과목_상세_입장하지_않은_학생은_형식보다_먼저_409_E15() throws Exception {
        String token = bearer(STUDENT);
        mvc.perform(get("/api/v1/courses/QZ300").header("Authorization", token))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("E15"));
        // 판정 순서: 학생 입장(E15) → 과목코드 형식(E09)
        mvc.perform(get("/api/v1/courses/BAD_CODE").header("Authorization", token))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("E15"));
    }

    @Test
    void 과목_상세_토큰_없으면_401() throws Exception {
        mvc.perform(get("/api/v1/courses/QZ300"))
                .andExpect(status().isUnauthorized());
    }

    // ---------------- 도우미 ----------------

    private void user(String id, String name, UserRole role) {
        if (!users.existsById(id)) {
            users.save(UserAccount.builder().userId(id).userName(name)
                    .passwordHash(new BCryptPasswordEncoder(10).encode(PASSWORD)).role(role).build());
        }
    }

    /** 과목 1건을 넣고 내부 ID를 돌려준다 */
    private String course(String code, String professorId, String title, String category, String grading,
                          int capacity, int minimum, int active, String status) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("""
                INSERT INTO courses (course_inst_id, course_code, professor_id, course_title, course_category,
                                     grading_type, max_capacity, min_enrollment, active_cnt, classroom,
                                     course_status, course_created_at)
                VALUES (:id, :code, :prof, :title, :cat, :grading, :cap, :min, :active, '공학관 301', :status, :now)""")
                .param("id", id).param("code", code).param("prof", professorId).param("title", title)
                .param("cat", category).param("grading", grading).param("cap", capacity).param("min", minimum)
                .param("active", active).param("status", status).param("now", Timestamp.from(Instant.now()))
                .update();
        return id;
    }

    /** 과목코드로 내역 1건을 넣는다 */
    /**
     * 내역 1건. STUDENT 구분은 같은 학생·같은 과목의 근거 티켓이, ADMIN 구분은 배정 관리자가 있어야 한다(V1 CHECK·FK).
     */
    private void enrollment(String studentId, String courseCode, String status, String type) {
        String courseInstId = jdbc.sql("SELECT course_inst_id FROM courses WHERE course_code = :code")
                .param("code", courseCode).query(String.class).single();
        Long sourceTicketId = null;
        String assignedBy = null;
        if ("STUDENT".equals(type)) {
            String sessionId = TestFixtures.session(jdbc, studentId, "STUDENT");
            sourceTicketId = TestFixtures.succeededEnrollmentTicket(jdbc, studentId, sessionId, courseInstId, courseCode);
        } else {
            assignedBy = ADMIN;
        }
        jdbc.sql("""
                INSERT INTO enrollments (student_id, course_inst_id, enroll_status, enroll_type, enrolled_at,
                                         assigned_by_user_id, source_ticket_id)
                VALUES (:s, :c, :status, :type, :t, :assignedBy, :ticket)""")
                .param("s", studentId).param("c", courseInstId).param("status", status).param("type", type)
                .param("t", Timestamp.from(Instant.now())).param("assignedBy", assignedBy)
                .param("ticket", sourceTicketId)
                .update();
    }

    /** 실제 로그인 절차(접수 → 대기열 처리 → 토큰 교환)로 Access 토큰을 받는다 */
    private String bearer(String userId) {
        AuthService.LoginReceipt receipt = auth.login(userId, PASSWORD.toCharArray());
        RScoredSortedSet<String> waiting = redis.getScoredSortedSet("waiting_queue");
        when(waiting.pollFirst(anyInt())).thenReturn(List.of(receipt.ticketId()));
        queue.admitWaitingUsers();
        when(waiting.pollFirst(anyInt())).thenReturn(List.of());
        return "Bearer " + auth.exchange(Long.parseLong(receipt.ticketId()), receipt.proof()).accessToken();
    }
}
