package com.crs.backend.course;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.crs.backend.TestFixtures;
import com.crs.backend.TestcontainersConfiguration;
import com.crs.backend.adapter.out.persistence.jpa.UserAccountRepository;
import com.crs.backend.admin.AdminEnrollmentRequest;
import com.crs.backend.admin.AdminEnrollmentService;
import com.crs.backend.auth.AuthService;
import com.crs.backend.auth.CurrentUser;
import com.crs.backend.domain.entity.UserAccount;
import com.crs.backend.domain.enums.UserRole;
import com.crs.backend.domain.service.ConcurrencyTestConfig;
import com.crs.backend.domain.service.EnrollmentService;
import com.crs.backend.queue.QueueService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * BE-09 관리자 배정(POST /admin/enrollments)과 교수 과목 수정(PUT /professor/courses/{code}).
 * 학생 수강신청과 같은 과목 잠금을 쓰는지 동시 실행으로도 확인한다(INV-2: active_cnt = ENROLLED 행 수).
 */
@SpringBootTest(properties = {
        "spring.task.scheduling.enabled=false",
        "queue.scheduler-fixed-delay-ms=3600000",
        "distributed-lock.wait-time=60",
        "distributed-lock.lease-time=30",
        "spring.autoconfigure.exclude=org.redisson.spring.starter.RedissonAutoConfigurationV2,org.redisson.spring.starter.RedissonAutoConfigurationV4"
})
@ActiveProfiles("concurrency-test")
@Import({ConcurrencyTestConfig.class, TestcontainersConfiguration.class})
@AutoConfigureMockMvc
class CourseWriteIntegrationTest {

    private static final String PASSWORD = "Test-password1!";
    private static final String ADMIN = "qzadmin";
    private static final String PROF_KIM = "qzprof1";
    private static final String PROF_PARK = "qzprof2";
    private static final String STUDENT = "qzstu1";
    private static final String STUDENT2 = "qzstu2";

    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired QueueService queue;
    @Autowired RedissonClient redis;
    @Autowired UserAccountRepository users;
    @Autowired JdbcClient jdbc;
    @Autowired EnrollmentService enrollmentService;
    @Autowired AdminEnrollmentService adminEnrollmentService;
    @Autowired ProfessorCourseService professorCourseService;

    @BeforeEach
    void seed() {
        TestFixtures.deleteCourses(jdbc, "QZ"); // 내역 → 티켓 → 과목 순서(FK)
        TestFixtures.deleteCourses(jdbc, "qz"); // 대소문자 구분 확인용 소문자 코드
        user(ADMIN, "관리자", UserRole.ADMIN);
        user(PROF_KIM, "김민수", UserRole.PROFESSOR);
        user(PROF_PARK, "박서연", UserRole.PROFESSOR);
        user(STUDENT, "학생1", UserRole.STUDENT);
        user(STUDENT2, "학생2", UserRole.STUDENT);

        course("QZ100", PROF_KIM, 40, 10, "OPEN");     // 일반
        course("QZ200", PROF_KIM, 35, 8, "CLOSED");    // 폐강
        String full = course("QZ300", PROF_KIM, 1, 1, "OPEN"); // 정원 1, 이미 1명
        enrollment(STUDENT2, full);
        course("QZ400", PROF_PARK, 30, 5, "OPEN");     // 박서연 교수 과목
    }

    // ===================== POST /admin/enrollments =====================

    @Test
    void 관리자는_정원이_찬_과목에도_배정할_수_있고_overCapacity가_true() throws Exception {
        mvc.perform(post("/api/v1/admin/enrollments").header("Authorization", bearer(ADMIN))
                        .contentType("application/json")
                        .content("{\"studentId\":\"" + STUDENT + "\",\"courseCode\":\"QZ300\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.studentId").value(STUDENT))
                .andExpect(jsonPath("$.courseCode").value("QZ300"))
                .andExpect(jsonPath("$.enrollmentType").value("ADMIN"))
                .andExpect(jsonPath("$.status").value("ENROLLED"))
                .andExpect(jsonPath("$.enrolledAt").value(endsWith("Z")))
                .andExpect(jsonPath("$.activeEnrollmentCount").value(2))
                .andExpect(jsonPath("$.capacity").value(1))
                .andExpect(jsonPath("$.overCapacity").value(true));

        // DB: ADMIN 행(배정 관리자 기록), 인원 2
        assertThat(jdbc.sql("""
                SELECT e.enroll_type || '/' || e.assigned_by_user_id FROM enrollments e JOIN courses c USING (course_inst_id)
                 WHERE c.course_code = 'QZ300' AND e.student_id = :s""").param("s", STUDENT)
                .query(String.class).single()).isEqualTo("ADMIN/" + ADMIN);
        assertThat(activeCnt("QZ300")).isEqualTo(2);
        assertInv2();
    }

    @Test
    void 정원_안이면_overCapacity_false() throws Exception {
        mvc.perform(adminAssign(STUDENT, "QZ100"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.activeEnrollmentCount").value(1))
                .andExpect(jsonPath("$.overCapacity").value(false));
    }

    @Test
    void 학생이_아니거나_없는_사용자는_422_E13() throws Exception {
        mvc.perform(adminAssign(PROF_KIM, "QZ100"))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("E13"));
        mvc.perform(adminAssign("nobody", "QZ100"))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("E13"));
        // 판정 순서: 학생 확인(E13)이 과목 없음(E04)보다 먼저
        mvc.perform(adminAssign("nobody", "NOPE999"))
                .andExpect(jsonPath("$.code").value("E13"));
    }

    @Test
    void 없는_과목_404_E04_폐강_409_E05_이미신청_409_E06() throws Exception {
        mvc.perform(adminAssign(STUDENT, "NOPE999"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("E04"));
        mvc.perform(adminAssign(STUDENT, "QZ200"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("E05"));
        mvc.perform(adminAssign(STUDENT2, "QZ300"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("E06"));
        assertThat(activeCnt("QZ300")).isEqualTo(1); // 실패한 요청은 인원을 바꾸지 않는다
    }

    @Test
    void 관리자_배정_본문_오류는_400_E09() throws Exception {
        String token = bearer(ADMIN);
        mvc.perform(post("/api/v1/admin/enrollments").header("Authorization", token).contentType("application/json")
                        .content("{\"studentId\":\"" + STUDENT + "\",\"courseCode\":\"bad-code!\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("E09"))
                .andExpect(jsonPath("$.violations[0].field").value("/courseCode"));
        mvc.perform(post("/api/v1/admin/enrollments").header("Authorization", token).contentType("application/json")
                        .content("{\"courseCode\":\"QZ100\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations[0].field").value("/studentId"));
        mvc.perform(post("/api/v1/admin/enrollments").header("Authorization", token).contentType("application/json")
                        .content("{\"studentId\":\"" + STUDENT + "\",\"courseCode\":\"QZ100\",\"force\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations[0].field").value("/force"));
    }

    @Test
    void 관리자가_아니면_403() throws Exception {
        mvc.perform(post("/api/v1/admin/enrollments").header("Authorization", bearer(PROF_KIM))
                        .contentType("application/json")
                        .content("{\"studentId\":\"" + STUDENT + "\",\"courseCode\":\"QZ100\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("E02"));
    }

    // ===================== PUT /professor/courses/{code} =====================

    @Test
    void 교수는_자기_과목을_수정하고_인원과_상태는_그대로다() throws Exception {
        mvc.perform(professorUpdate(PROF_KIM, "QZ100", body("데이터베이스2", "GENERAL", "PASS_FAIL", 50, 12, "공학관 305")))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.courseCode").value("QZ100"))
                .andExpect(jsonPath("$.name").value("데이터베이스2"))
                .andExpect(jsonPath("$.category").value("GENERAL"))
                .andExpect(jsonPath("$.evaluationType").value("PASS_FAIL"))
                .andExpect(jsonPath("$.capacity").value(50))
                .andExpect(jsonPath("$.minimumEnrollment").value(12))
                .andExpect(jsonPath("$.classroom").value("공학관 305"))
                .andExpect(jsonPath("$.professorName").value("김민수"))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.activeEnrollmentCount").value(0))
                .andExpect(jsonPath("$.enrolled").doesNotExist());
        assertThat(jdbc.sql("SELECT course_updated_at IS NOT NULL FROM courses WHERE course_code = 'QZ100'")
                .query(Boolean.class).single()).isTrue();
    }

    @Test
    void 정원_초과_상태여도_정원을_안_바꾸면_다른_필드는_수정된다() throws Exception {
        adminEnroll(STUDENT, "QZ300"); // 정원 1, 인원 2
        mvc.perform(professorUpdate(PROF_KIM, "QZ300", body("과목", "MAJOR", "RELATIVE", 1, 1, "새 강의실")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.classroom").value("새 강의실"))
                .andExpect(jsonPath("$.activeEnrollmentCount").value(2));
    }

    @Test
    void 정원을_바꿨고_새_정원이_현재_인원보다_작으면_409_E11() throws Exception {
        adminEnroll(STUDENT, "QZ300"); // 정원 1, 인원 2
        // QZ100을 정원 5, 인원 4로 만든 뒤 정원을 3으로 줄이면 E11
        // (최소 수강인원도 함께 내린다: courses CHECK min_enrollment BETWEEN 1 AND max_capacity)
        jdbc.sql("UPDATE courses SET max_capacity = 5, min_enrollment = 1, active_cnt = 4 WHERE course_code = 'QZ100'").update();
        mvc.perform(professorUpdate(PROF_KIM, "QZ100", body("과목", "MAJOR", "RELATIVE", 3, 1, "강의실")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("E11"));
        // 정원 1 → 2 (늘렸고 새 정원 2 = 인원 2): 허용
        mvc.perform(professorUpdate(PROF_KIM, "QZ300", body("과목", "MAJOR", "RELATIVE", 2, 1, "강의실")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capacity").value(2));
        // 줄였지만 새 정원 ≥ 인원: 허용
        mvc.perform(professorUpdate(PROF_KIM, "QZ100", body("과목", "MAJOR", "RELATIVE", 4, 1, "강의실")))
                .andExpect(status().isOk());
    }

    @Test
    void 남의_과목은_403_FORBIDDEN_폐강은_409_E05_없으면_404_E04() throws Exception {
        String req = body("과목", "MAJOR", "RELATIVE", 30, 5, "강의실");
        mvc.perform(professorUpdate(PROF_KIM, "QZ400", req))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("E02"))
                .andExpect(jsonPath("$.reason").value("FORBIDDEN"));
        mvc.perform(professorUpdate(PROF_KIM, "QZ200", req))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("E05"));
        mvc.perform(professorUpdate(PROF_KIM, "NOPE999", req))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("E04"));
    }

    @Test
    void 교수가_아니면_403() throws Exception {
        mvc.perform(professorUpdate(ADMIN, "QZ100", body("과목", "MAJOR", "RELATIVE", 30, 5, "강의실")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value("FORBIDDEN"));
    }

    @Test
    void 수정_본문_오류는_400_E09() throws Exception {
        // 최소 인원 > 정원
        mvc.perform(professorUpdate(PROF_KIM, "QZ100", body("과목", "MAJOR", "RELATIVE", 5, 6, "강의실")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations[0].field").value("/minimumEnrollment"));
        // 서버 소유 필드(courseCode)를 보내면 E09
        mvc.perform(put("/api/v1/professor/courses/QZ100").header("Authorization", bearer(PROF_KIM))
                        .contentType("application/json")
                        .content("{\"courseCode\":\"QZ999\",\"name\":\"과목\",\"category\":\"MAJOR\",\"evaluationType\":\"RELATIVE\","
                                + "\"capacity\":30,\"minimumEnrollment\":5,\"classroom\":\"강의실\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations[0].field").value("/courseCode"));
        // 필드 누락, 잘못된 열거값
        mvc.perform(put("/api/v1/professor/courses/QZ100").header("Authorization", bearer(PROF_KIM))
                        .contentType("application/json").content("{\"name\":\"과목\",\"category\":\"ETC\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations.length()").value(5));
        // 경로 과목코드 형식
        mvc.perform(professorUpdate(PROF_KIM, "BAD_CODE", body("과목", "MAJOR", "RELATIVE", 30, 5, "강의실")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations[0].source").value("PATH"));
    }

    // ===================== POST /professor/courses (BE-12) =====================

    @Test
    void 교수는_과목을_등록하고_201과_Location을_받는다() throws Exception {
        mvc.perform(post("/api/v1/professor/courses").header("Authorization", bearer(PROF_KIM))
                        .contentType("application/json")
                        .content(create("QZ900", "분산 시스템", "MAJOR", "ABSOLUTE", 30, 5, "공학관 410")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/courses/QZ900"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.courseCode").value("QZ900"))
                .andExpect(jsonPath("$.name").value("분산 시스템"))
                .andExpect(jsonPath("$.category").value("MAJOR"))
                .andExpect(jsonPath("$.evaluationType").value("ABSOLUTE"))
                .andExpect(jsonPath("$.professorName").value("김민수"))
                .andExpect(jsonPath("$.capacity").value(30))
                .andExpect(jsonPath("$.minimumEnrollment").value(5))
                .andExpect(jsonPath("$.activeEnrollmentCount").value(0))
                .andExpect(jsonPath("$.remainingSeats").value(30))
                .andExpect(jsonPath("$.classroom").value("공학관 410"))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.enrolled").doesNotExist());
        // 담당교수는 본문이 아니라 로그인한 교수로 정해진다
        assertThat(jdbc.sql("SELECT professor_id FROM courses WHERE course_code = 'QZ900'")
                .query(String.class).single()).isEqualTo(PROF_KIM);
    }

    @Test
    void 이미_있는_코드는_폐강_과목이라도_409_E10() throws Exception {
        String token = bearer(PROF_KIM);
        mvc.perform(post("/api/v1/professor/courses").header("Authorization", token).contentType("application/json")
                        .content(create("QZ100", "과목", "MAJOR", "RELATIVE", 30, 5, "강의실")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("E10"));
        mvc.perform(post("/api/v1/professor/courses").header("Authorization", token).contentType("application/json")
                        .content(create("QZ200", "과목", "MAJOR", "RELATIVE", 30, 5, "강의실")))  // QZ200은 폐강
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("E10"));
        // 대소문자를 구분하므로 소문자 qz100은 다른 코드다
        mvc.perform(post("/api/v1/professor/courses").header("Authorization", token).contentType("application/json")
                        .content(create("qz100", "과목", "MAJOR", "RELATIVE", 30, 5, "강의실")))
                .andExpect(status().isCreated());
    }

    @Test
    void 같은_코드로_동시에_등록하면_하나만_성공하고_나머지는_E10() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(5);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        try {
            for (int i = 0; i < 5; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    try {
                        professorCourseService.create(new CurrentUser(PROF_KIM, "s", UserRole.PROFESSOR),
                                new CourseCreateRequest("QZ950", "동시 등록", "MAJOR", "RELATIVE", 10, 1, "강의실"));
                        return "OK";
                    } catch (com.crs.backend.common.CrsException e) {
                        return e.getCode();
                    }
                }));
            }
            start.countDown();
            List<String> codes = new ArrayList<>();
            for (Future<String> f : results) codes.add(f.get(30, TimeUnit.SECONDS));
            assertThat(codes).containsOnly("OK", "E10");
            assertThat(codes.stream().filter("OK"::equals).count()).isEqualTo(1);
            assertThat(jdbc.sql("SELECT count(*) FROM courses WHERE course_code = 'QZ950'")
                    .query(Long.class).single()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void 등록_본문_오류는_400_E09() throws Exception {
        String token = bearer(PROF_KIM);
        // 과목코드 형식
        mvc.perform(post("/api/v1/professor/courses").header("Authorization", token).contentType("application/json")
                        .content(create("BAD-CODE!", "과목", "MAJOR", "RELATIVE", 30, 5, "강의실")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations[0].field").value("/courseCode"));
        // 공백만 있는 과목명
        mvc.perform(post("/api/v1/professor/courses").header("Authorization", token).contentType("application/json")
                        .content(create("QZ901", "   ", "MAJOR", "RELATIVE", 30, 5, "강의실")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations[0].field").value("/name"));
        // 최소 인원 > 정원
        mvc.perform(post("/api/v1/professor/courses").header("Authorization", token).contentType("application/json")
                        .content(create("QZ902", "과목", "MAJOR", "RELATIVE", 5, 6, "강의실")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations[0].field").value("/minimumEnrollment"));
        // 서버 소유 필드(담당교수)를 보내면 E09
        mvc.perform(post("/api/v1/professor/courses").header("Authorization", token).contentType("application/json")
                        .content("{\"courseCode\":\"QZ903\",\"name\":\"과목\",\"category\":\"MAJOR\",\"evaluationType\":\"RELATIVE\","
                                + "\"capacity\":30,\"minimumEnrollment\":5,\"classroom\":\"강의실\",\"professorId\":\"" + PROF_PARK + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations[0].field").value("/professorId"));
        // 필수 필드 누락(과목코드, 이름, 이수구분, 평가유형, 정원, 최소 인원, 강의실 = 7개)
        mvc.perform(post("/api/v1/professor/courses").header("Authorization", token).contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations.length()").value(7));
        // 정수 범위를 넘는 정원
        mvc.perform(post("/api/v1/professor/courses").header("Authorization", token).contentType("application/json")
                        .content("{\"courseCode\":\"QZ904\",\"name\":\"과목\",\"category\":\"MAJOR\",\"evaluationType\":\"RELATIVE\","
                                + "\"capacity\":2147483648,\"minimumEnrollment\":5,\"classroom\":\"강의실\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("E09"));
        // 아무것도 등록되지 않았다
        assertThat(jdbc.sql("SELECT count(*) FROM courses WHERE course_code IN ('QZ901','QZ902','QZ903','QZ904')")
                .query(Long.class).single()).isZero();
    }

    @Test
    void 교수가_아니면_등록할_수_없다_403() throws Exception {
        mvc.perform(post("/api/v1/professor/courses").header("Authorization", bearer(ADMIN))
                        .contentType("application/json")
                        .content(create("QZ905", "과목", "MAJOR", "RELATIVE", 30, 5, "강의실")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("E02"))
                .andExpect(jsonPath("$.reason").value("FORBIDDEN"));
    }

    // ===================== 동시 실행(INV-2) =====================

    @Test
    void 학생_신청_관리자_배정_교수_수정이_동시에_와도_인원이_어긋나지_않는다() throws Exception {
        String courseInstId = course("QZ700", PROF_KIM, 3, 1, "OPEN");
        List<String> admins = List.of("qzadm1", "qzadm2", "qzadm3"); // 관리자가 배정할 학생 3명
        for (String s : admins) user(s, s, UserRole.STUDENT);
        // 학생 신청 15명: 계정, 세션, 근거 티켓(STUDENT 내역은 같은 학생·과목의 티켓이 있어야 함)
        java.util.Map<String, Long> tickets = new java.util.HashMap<>();
        for (int i = 0; i < 15; i++) {
            String studentId = "qzc" + i;
            user(studentId, studentId, UserRole.STUDENT);
            String sessionId = TestFixtures.session(jdbc, studentId, "STUDENT");
            tickets.put(studentId, TestFixtures.waitingEnrollmentTicket(jdbc, studentId, sessionId, courseInstId, "QZ700"));
        }

        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> studentResults = new ArrayList<>();
        List<Future<Boolean>> adminResults = new ArrayList<>();
        try {
            for (int i = 0; i < 15; i++) {
                String studentId = "qzc" + i;
                studentResults.add(pool.submit(() -> {
                    start.await();
                    try {
                        enrollmentService.enrollStudent(studentId, courseInstId, tickets.get(studentId));
                        return true;
                    } catch (RuntimeException rejected) {
                        return false;
                    }
                }));
            }
            for (String s : admins) {
                adminResults.add(pool.submit(() -> {
                    start.await();
                    adminEnrollmentService.assign(new CurrentUser(ADMIN, "s", UserRole.ADMIN),
                            new AdminEnrollmentRequest(s, "QZ700"));
                    return true;
                }));
            }
            List<Future<?>> updates = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                updates.add(pool.submit(() -> {
                    start.await();
                    return professorCourseService.update(new CurrentUser(PROF_KIM, "s", UserRole.PROFESSOR), "QZ700",
                            new CourseUpdateRequest("동시성", "MAJOR", "RELATIVE", 3, 1, "공학관 999"));
                }));
            }
            start.countDown();

            int studentOk = 0;
            for (Future<Boolean> f : studentResults) if (f.get(60, TimeUnit.SECONDS)) studentOk++;
            int adminOk = 0;
            for (Future<Boolean> f : adminResults) if (f.get(60, TimeUnit.SECONDS)) adminOk++;
            for (Future<?> f : updates) f.get(60, TimeUnit.SECONDS);

            long enrolled = jdbc.sql("SELECT count(*) FROM enrollments WHERE course_inst_id = :c AND enroll_status = 'ENROLLED'")
                    .param("c", courseInstId).query(Long.class).single();

            assertThat(adminOk).isEqualTo(3);                       // 관리자 배정은 정원과 무관하게 모두 성공
            assertThat(studentOk).isLessThanOrEqualTo(3);           // 학생 신청은 정원 3을 넘지 않음
            assertThat(enrolled).isEqualTo(studentOk + adminOk);
            assertThat(activeCnt("QZ700")).isEqualTo((int) enrolled); // 교수 수정이 인원을 덮어쓰지 않음
            assertThat(jdbc.sql("SELECT classroom FROM courses WHERE course_code = 'QZ700'")
                    .query(String.class).single()).isEqualTo("공학관 999");
            assertInv2();
        } finally {
            pool.shutdownNow();
        }
    }

    // ===================== 도우미 =====================

    private org.springframework.test.web.servlet.RequestBuilder adminAssign(String studentId, String courseCode) {
        return post("/api/v1/admin/enrollments").header("Authorization", bearer(ADMIN))
                .contentType("application/json")
                .content("{\"studentId\":\"" + studentId + "\",\"courseCode\":\"" + courseCode + "\"}");
    }

    private void adminEnroll(String studentId, String courseCode) {
        adminEnrollmentService.assign(new CurrentUser(ADMIN, "s", UserRole.ADMIN),
                new AdminEnrollmentRequest(studentId, courseCode));
    }

    private org.springframework.test.web.servlet.RequestBuilder professorUpdate(String userId, String code, String json) {
        return put("/api/v1/professor/courses/" + code).header("Authorization", bearer(userId))
                .contentType("application/json").content(json);
    }

    private static String create(String code, String name, String category, String eval, int capacity, int minimum,
                                 String classroom) {
        return "{\"courseCode\":\"" + code + "\"," + body(name, category, eval, capacity, minimum, classroom).substring(1);
    }

    private static String body(String name, String category, String eval, int capacity, int minimum, String classroom) {
        return "{\"name\":\"" + name + "\",\"category\":\"" + category + "\",\"evaluationType\":\"" + eval
                + "\",\"capacity\":" + capacity + ",\"minimumEnrollment\":" + minimum
                + ",\"classroom\":\"" + classroom + "\"}";
    }

    private int activeCnt(String code) {
        return jdbc.sql("SELECT active_cnt FROM courses WHERE course_code = :c").param("c", code)
                .query(Integer.class).single();
    }

    /** INV-2: 모든 QZ 과목에서 active_cnt = ENROLLED 행 수 */
    private void assertInv2() {
        long broken = jdbc.sql("""
                SELECT count(*) FROM (
                  SELECT c.course_inst_id
                    FROM courses c
                    LEFT JOIN enrollments e ON e.course_inst_id = c.course_inst_id AND e.enroll_status = 'ENROLLED'
                   WHERE c.course_code LIKE 'QZ%'
                   GROUP BY c.course_inst_id, c.active_cnt
                  HAVING c.active_cnt <> count(e.student_id)) x""").query(Long.class).single();
        assertThat(broken).isZero();
    }

    private void user(String id, String name, UserRole role) {
        if (!users.existsById(id)) {
            users.save(UserAccount.builder().userId(id).userName(name)
                    .passwordHash(new BCryptPasswordEncoder(4).encode(PASSWORD)).role(role).build());
        }
    }

    private String course(String code, String professorId, int capacity, int minimum, String status) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("""
                INSERT INTO courses (course_inst_id, course_code, professor_id, course_title, course_category,
                                     grading_type, max_capacity, min_enrollment, active_cnt, classroom,
                                     course_status, course_created_at)
                VALUES (:id, :code, :prof, '과목', 'MAJOR', 'RELATIVE', :cap, :min, 0, '공학관 101', :status, :now)""")
                .param("id", id).param("code", code).param("prof", professorId).param("cap", capacity)
                .param("min", minimum).param("status", status).param("now", Timestamp.from(Instant.now()))
                .update();
        return id;
    }

    /** 내역 1건 + 인원 +1 (INV-2를 지킨 채로 준비) */
    private void enrollment(String studentId, String courseInstId) {
        jdbc.sql("""
                INSERT INTO enrollments (student_id, course_inst_id, enroll_status, enroll_type, enrolled_at, assigned_by_user_id)
                VALUES (:s, :c, 'ENROLLED', 'ADMIN', :t, :admin)""")
                .param("s", studentId).param("c", courseInstId).param("t", Timestamp.from(Instant.now()))
                .param("admin", ADMIN).update();
        jdbc.sql("UPDATE courses SET active_cnt = active_cnt + 1 WHERE course_inst_id = :c").param("c", courseInstId).update();
    }

    private String bearer(String userId) {
        AuthService.LoginReceipt receipt = auth.login(userId, PASSWORD.toCharArray());
        RScoredSortedSet<String> waiting = redis.getScoredSortedSet("waiting_queue");
        when(waiting.pollFirst(anyInt())).thenReturn(List.of(receipt.ticketId()));
        queue.admitWaitingUsers();
        when(waiting.pollFirst(anyInt())).thenReturn(List.of());
        return "Bearer " + auth.exchange(Long.parseLong(receipt.ticketId()), receipt.proof()).accessToken();
    }
}
