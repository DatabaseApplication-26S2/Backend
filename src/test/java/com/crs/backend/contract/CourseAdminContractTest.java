package com.crs.backend.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.crs.backend.TestFixtures;
import com.crs.backend.TestcontainersConfiguration;
import com.crs.backend.adapter.out.persistence.jpa.UserAccountRepository;
import com.crs.backend.application.TicketDispatcher;
import com.crs.backend.auth.AuthService;
import com.crs.backend.auth.OpenApiResponses;
import com.crs.backend.domain.entity.UserAccount;
import com.crs.backend.domain.enums.UserRole;
import com.crs.backend.domain.service.ConcurrencyTestConfig;
import com.crs.backend.queue.QueueService;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * BE-16(#8b): 과목·학생 신청·교수·관리자 연산 14개의 응답을 동결 계약(contract/openapi.yaml)으로 검증한다.
 * 연산마다 성공 1건과 대표 오류 1건을 실제로 호출하고, #8a의 OpenApiResponses.verify로
 * 선언된 상태 코드·헤더·콘텐츠 타입·본문 스키마(JSON Schema 2020-12, 형식 검사 포함)를 확인한다.
 * 인증 연산 6개는 AuthIntegrationTest(#8a)가 검증한다. 둘을 합쳐 20개 연산이다.
 *
 * 실행 환경은 CourseWriteIntegrationTest와 같게 두어 Spring 컨텍스트를 함께 쓴다. 테스트 데이터는 QC 접두어다.
 */
@SpringBootTest(properties = {
        "spring.task.scheduling.enabled=false",
        "queue.scheduler-fixed-delay-ms=3600000",
        "spring.autoconfigure.exclude=org.redisson.spring.starter.RedissonAutoConfigurationV2,org.redisson.spring.starter.RedissonAutoConfigurationV4"
})
@ActiveProfiles({"test", "concurrency-test"})
@Import({ConcurrencyTestConfig.class, TestcontainersConfiguration.class})
@AutoConfigureMockMvc
class CourseAdminContractTest {

    private static final String PASSWORD = "Test-password1!";
    private static final String ADMIN = "qcadmin";
    private static final String PROF = "qcprof1";
    private static final String OTHER_PROF = "qcprof2";
    private static final String STUDENT = "qcstu1";
    private static final String ASSIGNEE = "qcstu2";

    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired QueueService queue;
    @Autowired TicketDispatcher dispatcher;
    @Autowired UserAccountRepository users;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void seed() {
        TestFixtures.deleteCourses(jdbc, "QC"); // 내역 → 티켓 → 과목 순서(FK)
        user(ADMIN, UserRole.ADMIN);
        user(PROF, UserRole.PROFESSOR);
        user(OTHER_PROF, UserRole.PROFESSOR);
        user(STUDENT, UserRole.STUDENT);
        user(ASSIGNEE, UserRole.STUDENT);
        course("QC100", PROF, 30, 1, "OPEN");        // 신청·수정·상세용
        course("QC200", PROF, 30, 1, "CLOSED");      // 폐강 과목
        course("QC300", OTHER_PROF, 30, 1, "OPEN");  // 다른 교수 과목
        course("QC400", PROF, 30, 5, "OPEN");        // 교수 폐강 대상(인원 0 < 최소 5)
        course("QC500", PROF, 30, 5, "OPEN");        // 관리자 일괄 폐강 대상
    }

    /** 같은 컨텍스트(DB)를 쓰는 다른 테스트가 전체 목록(예: 폐강 대상)을 단언하므로, 만든 과목을 남기지 않는다 */
    @AfterEach
    void cleanUp() {
        TestFixtures.deleteCourses(jdbc, "QC");
    }

    // ===================== 과목 (2) =====================

    @Test
    void 과목_검색과_상세() throws Exception {
        String student = admittedStudent();
        contract("/courses", "get", get("/api/v1/courses?page=1").header("Authorization", student), 200);
        contract("/courses", "get", get("/api/v1/courses?page=0").header("Authorization", student), 400);

        contract("/courses/{courseCode}", "get", get("/api/v1/courses/QC100").header("Authorization", student), 200);
        contract("/courses/{courseCode}", "get", get("/api/v1/courses/QC999").header("Authorization", student), 404);
    }

    // ===================== 학생 신청 (3) =====================

    @Test
    void 수강_신청_내역_취소() throws Exception {
        String student = admittedStudent();

        contract("/me/enrollments", "post", post("/api/v1/me/enrollments").header("Authorization", student)
                .contentType("application/json").content("{\"courseCode\":\"QC100\"}"), 202);
        drain();
        contract("/me/enrollments", "post", post("/api/v1/me/enrollments").header("Authorization", student)
                .contentType("application/json").content("{\"courseCode\":\"QC999\"}"), 404);

        contract("/me/enrollments", "get", get("/api/v1/me/enrollments").header("Authorization", student), 200);
        contract("/me/enrollments", "get", get("/api/v1/me/enrollments").header("Authorization", bearer(PROF)), 403);

        contract("/me/enrollments/{courseCode}", "delete", delete("/api/v1/me/enrollments/QC100").header("Authorization", student), 202);
        drain();
        contract("/me/enrollments/{courseCode}", "delete", delete("/api/v1/me/enrollments/QC999").header("Authorization", student), 404);
    }

    // ===================== 교수 (4) =====================

    @Test
    void 교수_담당과목_등록_수정_폐강() throws Exception {
        String prof = bearer(PROF);

        contract("/professor/courses", "get", get("/api/v1/professor/courses").header("Authorization", prof), 200);
        contract("/professor/courses", "get", get("/api/v1/professor/courses").header("Authorization", admittedStudent()), 403);

        String create = "{\"courseCode\":\"QC600\",\"name\":\"계약 점검\",\"category\":\"MAJOR\",\"evaluationType\":\"ABSOLUTE\","
                + "\"capacity\":20,\"minimumEnrollment\":3,\"classroom\":\"점검실\"}";
        contract("/professor/courses", "post", post("/api/v1/professor/courses").header("Authorization", prof)
                .contentType("application/json").content(create), 201);
        contract("/professor/courses", "post", post("/api/v1/professor/courses").header("Authorization", prof)
                .contentType("application/json").content(create), 409); // E10 같은 과목코드

        String update = "{\"name\":\"과목\",\"category\":\"MAJOR\",\"evaluationType\":\"RELATIVE\","
                + "\"capacity\":30,\"minimumEnrollment\":1,\"classroom\":\"공학관 202\"}";
        contract("/professor/courses/{courseCode}", "put", put("/api/v1/professor/courses/QC100").header("Authorization", prof)
                .contentType("application/json").content(update), 200);
        contract("/professor/courses/{courseCode}", "put", put("/api/v1/professor/courses/QC300").header("Authorization", prof)
                .contentType("application/json").content(update), 403); // 남의 과목

        contract("/professor/courses/{courseCode}/closure", "post",
                post("/api/v1/professor/courses/QC400/closure").header("Authorization", prof), 200);
        contract("/professor/courses/{courseCode}/closure", "post",
                post("/api/v1/professor/courses/QC400/closure").header("Authorization", prof), 409); // E14 이미 폐강
    }

    // ===================== 관리자 (5) =====================

    @Test
    void 관리자_배정_폐강대상_일괄폐강() throws Exception {
        String admin = bearer(ADMIN);

        contract("/admin/enrollments", "post", post("/api/v1/admin/enrollments").header("Authorization", admin)
                .contentType("application/json").content("{\"studentId\":\"" + ASSIGNEE + "\",\"courseCode\":\"QC100\"}"), 201);
        contract("/admin/enrollments", "post", post("/api/v1/admin/enrollments").header("Authorization", admin)
                .contentType("application/json").content("{\"studentId\":\"" + ASSIGNEE + "\",\"courseCode\":\"QC200\"}"), 409); // E05

        contract("/admin/closure-candidates", "get", get("/api/v1/admin/closure-candidates?page=1").header("Authorization", admin), 200);
        contract("/admin/closure-candidates", "get", get("/api/v1/admin/closure-candidates?page=0").header("Authorization", admin), 400);

        contract("/admin/course-closures", "post", post("/api/v1/admin/course-closures").header("Authorization", admin)
                .contentType("application/json").content("{\"courseCodes\":[\"QC500\",\"QC999\"]}"), 200); // OK·E04 항목
        contract("/admin/course-closures", "post", post("/api/v1/admin/course-closures").header("Authorization", admin)
                .contentType("application/json").content("{\"courseCodes\":[]}"), 400);
    }

    @Test
    void 관리자_서비스_설정_조회와_변경() throws Exception {
        String admin = bearer(ADMIN);
        int n = jdbc.sql("SELECT concurrency_limit FROM service_settings").query(Integer.class).single();

        contract("/admin/settings", "get", get("/api/v1/admin/settings").header("Authorization", admin), 200);
        contract("/admin/settings", "get", get("/api/v1/admin/settings").header("Authorization", bearer(PROF)), 403);

        // 같은 값으로 저장해 다른 테스트가 쓰는 N을 바꾸지 않는다(같은 값은 변경자·시각도 그대로다)
        contract("/admin/settings", "put", put("/api/v1/admin/settings").header("Authorization", admin)
                .contentType("application/json").content("{\"concurrencyLimit\":" + n + "}"), 200);
        contract("/admin/settings", "put", put("/api/v1/admin/settings").header("Authorization", admin)
                .contentType("application/json").content("{\"concurrencyLimit\":0}"), 400);
        assertThat(jdbc.sql("SELECT concurrency_limit FROM service_settings").query(Integer.class).single()).isEqualTo(n);
    }

    // ===================== 도우미 =====================

    /** 요청을 보내 상태 코드를 확인하고, 응답 전체를 동결 계약으로 검증한다(#8a와 같은 방식) */
    private MockHttpServletResponse contract(String path, String method, MockHttpServletRequestBuilder request,
                                             int expectedStatus) throws Exception {
        MockHttpServletResponse response = mvc.perform(request).andExpect(status().is(expectedStatus)).andReturn().getResponse();
        OpenApiResponses.verify(path, method, response);
        return response;
    }

    /** 접수된 티켓을 claim하고 핸들러가 끝날 때까지 기다린다(PR #14부터 핸들러는 비동기로 돈다) */
    private void drain() {
        queue.admitWaitingUsers();
        assertThat(dispatcher.awaitIdle(Duration.ofSeconds(10))).isTrue();
    }

    private String bearer(String userId) {
        AuthService.LoginReceipt receipt = auth.login(userId, PASSWORD.toCharArray());
        drain();
        return "Bearer " + auth.exchange(Long.parseLong(receipt.ticketId()), receipt.proof()).accessToken();
    }

    /** 수강신청 화면에 입장한 학생의 Access 토큰 */
    private String admittedStudent() {
        String token = bearer(STUDENT);
        var user = auth.authenticate(token.substring("Bearer ".length()));
        auth.requestAdmission(user.userId(), user.sessionId());
        drain();
        assertThat(auth.isAdmitted(user.userId(), user.sessionId())).isTrue();
        return token;
    }

    private void user(String id, UserRole role) {
        if (!users.existsById(id)) {
            users.save(UserAccount.builder().userId(id).userName(id)
                    .passwordHash(new BCryptPasswordEncoder(4).encode(PASSWORD)).role(role).build());
        }
    }

    private void course(String code, String professorId, int capacity, int minimum, String status) {
        jdbc.sql("""
                INSERT INTO courses (course_inst_id, course_code, professor_id, course_title, course_category,
                                     grading_type, max_capacity, min_enrollment, active_cnt, classroom,
                                     course_status, course_created_at)
                VALUES (:id, :code, :prof, '과목', 'MAJOR', 'RELATIVE', :cap, :min, 0, '공학관 101', :status, :now)""")
                .param("id", UUID.randomUUID().toString()).param("code", code).param("prof", professorId)
                .param("cap", capacity).param("min", minimum).param("status", status)
                .param("now", Timestamp.from(Instant.now()))
                .update();
    }
}
