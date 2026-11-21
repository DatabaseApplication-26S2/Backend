package com.crs.backend.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.crs.backend.TestFixtures;
import com.crs.backend.TestcontainersConfiguration;
import com.crs.backend.adapter.out.persistence.jpa.UserAccountRepository;
import com.crs.backend.application.TicketDispatcher;
import com.crs.backend.auth.AuthService;
import com.crs.backend.auth.CurrentUser;
import com.crs.backend.domain.entity.UserAccount;
import com.crs.backend.domain.enums.UserRole;
import com.crs.backend.domain.service.ConcurrencyTestConfig;
import com.crs.backend.queue.QueueService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * BE-14 서비스 설정(ADM-02-01): GET·PUT /admin/settings.
 *
 * 완료 조건
 *   - N을 올리면 곧바로 추가 claim이 일어난다(스케줄러는 꺼 두고, 변경 직후의 디스패처 호출만으로 확인).
 *   - N을 내리면 처리 중 수 ≥ N인 동안 신규 claim이 0이다.
 *   - 범위 밖 값은 400 E09다.
 *
 * claim 수를 정확히 세려고 다른 테스트와 DB를 나누지 않도록 이 클래스만의 컨텍스트(새 PostgreSQL 컨테이너)를 쓴다.
 */
@SpringBootTest(properties = {
        "spring.task.scheduling.enabled=false",
        "queue.scheduler-fixed-delay-ms=3600000",
        "crs.test.context=service-settings", // 이 클래스 전용 컨텍스트(전용 DB)
        "spring.autoconfigure.exclude=org.redisson.spring.starter.RedissonAutoConfigurationV2,org.redisson.spring.starter.RedissonAutoConfigurationV4"
})
@ActiveProfiles({"test", "concurrency-test"})
@Import({ConcurrencyTestConfig.class, TestcontainersConfiguration.class})
@AutoConfigureMockMvc
class ServiceSettingsIntegrationTest {

    private static final String PASSWORD = "Test-password1!";
    private static final String ADMIN = "qsadmin";
    private static final String PROF = "qsprof";
    private static final CurrentUser ADMIN_USER = new CurrentUser(ADMIN, "s", UserRole.ADMIN);

    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired QueueService queue;
    @Autowired TicketDispatcher dispatcher;
    @Autowired ServiceSettingsService settings;
    @Autowired UserAccountRepository users;
    @Autowired JdbcClient jdbc;

    private int seq;

    @BeforeEach
    void reset() {
        TestFixtures.deleteCourses(jdbc, "QS"); // 내역 → 티켓 → 과목
        jdbc.sql("UPDATE service_settings SET concurrency_limit = 100, last_changed_by = NULL, last_changed_at = NULL")
                .update();
        user(ADMIN, UserRole.ADMIN);
        user(PROF, UserRole.PROFESSOR);
    }

    // ===================== GET /admin/settings =====================

    @Test
    void 초기_상태_조회는_N_100과_변경자_null() throws Exception {
        mvc.perform(get("/api/v1/admin/settings").header("Authorization", bearer(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.concurrencyLimit").value(100))
                .andExpect(jsonPath("$.activeSlots").value(0))
                .andExpect(jsonPath("$.availableSlots").value(100))
                .andExpect(jsonPath("$.waitingCount").value(0))
                .andExpect(jsonPath("$.lastChangedBy").value(nullValue()))
                .andExpect(jsonPath("$.lastChangedAt").value(nullValue()));
    }

    @Test
    void 정의되지_않은_쿼리는_400_E09() throws Exception {
        mvc.perform(get("/api/v1/admin/settings?x=1").header("Authorization", bearer(ADMIN)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("E09"));
    }

    @Test
    void 교수는_조회도_변경도_403_E02() throws Exception {
        String token = bearer(PROF);
        mvc.perform(get("/api/v1/admin/settings").header("Authorization", token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("E02"));
        mvc.perform(put("/api/v1/admin/settings").header("Authorization", token)
                        .contentType("application/json").content("{\"concurrencyLimit\":5}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("E02"));
        assertThat(limit()).isEqualTo(100);
    }

    // ===================== PUT /admin/settings =====================

    @Test
    void 변경하면_N과_변경자_변경시각을_기록한다() throws Exception {
        mvc.perform(put("/api/v1/admin/settings").header("Authorization", bearer(ADMIN))
                        .contentType("application/json").content("{\"concurrencyLimit\":20}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.concurrencyLimit").value(20))
                .andExpect(jsonPath("$.lastChangedBy").value(ADMIN))
                .andExpect(jsonPath("$.lastChangedAt").value(endsWith("Z")));

        assertThat(limit()).isEqualTo(20);
        assertThat(jdbc.sql("SELECT last_changed_by FROM service_settings").query(String.class).single())
                .isEqualTo(ADMIN);
    }

    @Test
    void 같은_값이면_변경자와_시각을_바꾸지_않는다() {
        settings.update(ADMIN_USER, new ServiceSettingsUpdateRequest(20));
        Timestamp first = changedAt();
        user("qsadmin2", UserRole.ADMIN);

        // 다른 관리자가 같은 값을 보내도 변경자는 처음 변경한 사람 그대로다
        ServiceSettingsView again = settings.update(new CurrentUser("qsadmin2", "s", UserRole.ADMIN),
                new ServiceSettingsUpdateRequest(20));

        assertThat(again.concurrencyLimit()).isEqualTo(20);
        assertThat(changedAt()).isEqualTo(first);
        assertThat(changedBy()).isEqualTo(ADMIN);
    }

    @Test
    void 범위_밖이거나_빠지거나_추가_필드면_400_E09이고_N은_그대로() throws Exception {
        String token = bearer(ADMIN);
        for (String body : List.of("{\"concurrencyLimit\":0}", "{\"concurrencyLimit\":1001}",
                "{\"concurrencyLimit\":-1}")) {
            mvc.perform(put("/api/v1/admin/settings").header("Authorization", token)
                            .contentType("application/json").content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("E09"))
                    .andExpect(jsonPath("$.violations[0].source").value("BODY"))
                    .andExpect(jsonPath("$.violations[0].field").value("/concurrencyLimit"));
        }
        for (String body : List.of("{}", "{\"concurrencyLimit\":5,\"other\":1}")) {
            mvc.perform(put("/api/v1/admin/settings").header("Authorization", token)
                            .contentType("application/json").content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("E09"));
        }
        assertThat(limit()).isEqualTo(100);
        assertThat(changedAt()).isNull();
    }

    @Test
    void 정수가_아닌_타입의_N은_400_E09이고_N은_그대로() throws Exception {
        String token = bearer(ADMIN);
        for (String body : List.of("{\"concurrencyLimit\":\"5\"}", "{\"concurrencyLimit\":1.5}",
                "{\"concurrencyLimit\":true}", "{\"concurrencyLimit\":{}}", "{\"concurrencyLimit\":[5]}")) {
            mvc.perform(put("/api/v1/admin/settings").header("Authorization", token)
                            .contentType("application/json").content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("E09"))
                    .andExpect(jsonPath("$.violations[0].source").value("BODY"))
                    .andExpect(jsonPath("$.violations[0].field").value("/concurrencyLimit"));
        }
        assertThat(limit()).isEqualTo(100);
        assertThat(changedAt()).isNull();
        assertThat(changedBy()).isNull();
    }

    @Test
    void 정수값인_소수_표기_20_0은_20으로_허용한다() throws Exception {
        mvc.perform(put("/api/v1/admin/settings").header("Authorization", bearer(ADMIN))
                        .contentType("application/json").content("{\"concurrencyLimit\":20.0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.concurrencyLimit").value(20))
                .andExpect(jsonPath("$.lastChangedBy").value(ADMIN));

        assertThat(limit()).isEqualTo(20); // 다음 테스트는 @BeforeEach에서 100으로 되돌린다
    }

    @Test
    void 경계값_1과_1000은_허용한다() {
        assertThat(settings.update(ADMIN_USER, new ServiceSettingsUpdateRequest(1)).concurrencyLimit()).isEqualTo(1);
        assertThat(settings.update(ADMIN_USER, new ServiceSettingsUpdateRequest(1000)).concurrencyLimit()).isEqualTo(1000);
    }

    // ===================== claim에 미치는 효과 =====================

    @Test
    void N을_올리면_스케줄러를_기다리지_않고_곧바로_추가_claim한다() throws Exception {
        setLimit(1);
        processingTicket();                  // 처리 중 1 = N → 여유 0
        List<Long> waiting = new ArrayList<>();
        for (int i = 0; i < 3; i++) waiting.add(waitingTicket());

        assertThat(dispatcher.claimAndDispatch()).isZero(); // 올리기 전: 신규 claim 없음
        assertThat(statusOf(waiting.get(0))).isEqualTo("WAITING");

        ServiceSettingsView view = settings.update(ADMIN_USER, new ServiceSettingsUpdateRequest(3));
        assertThat(view.concurrencyLimit()).isEqualTo(3);

        // 스케줄러는 꺼져 있다. 변경 직후 디스패처가 늘어난 여유 2만큼 번호 순으로 claim해야 한다
        awaitTrue(() -> !"WAITING".equals(statusOf(waiting.get(0))) && !"WAITING".equals(statusOf(waiting.get(1))));
        Thread.sleep(300); // 늦게 끝난 claim이 더 가져가지 않는지 잠시 지켜본다
        assertThat(statusOf(waiting.get(2))).isEqualTo("WAITING"); // 여유를 넘겨 claim하지 않음(FIFO)
    }

    @Test
    void N을_내리면_처리_중_수가_N_이상인_동안_신규_claim이_0이다() {
        setLimit(3);
        List<Long> processing = new ArrayList<>();
        for (int i = 0; i < 3; i++) processing.add(processingTicket());
        long waiting = waitingTicket();

        ServiceSettingsView view = settings.update(ADMIN_USER, new ServiceSettingsUpdateRequest(1));
        assertThat(view.concurrencyLimit()).isEqualTo(1);
        assertThat(view.activeSlots()).isEqualTo(3);     // 진행 중 작업은 중단하지 않는다
        assertThat(view.availableSlots()).isZero();
        assertThat(statusOf(processing.get(0))).isEqualTo("PROCESSING");

        // 처리 중 3 → 2 → 1: 모두 N(1) 이상이라 신규 claim 0
        for (int i = 0; i < 3; i++) {
            dispatcher.claimAndDispatch();
            assertThat(dispatcher.awaitIdle(java.time.Duration.ofSeconds(10))).isTrue();
            assertThat(statusOf(waiting)).isEqualTo("WAITING");
            finish(processing.get(i));
        }
        // 처리 중 0 < N: 이제 claim된다
        dispatcher.claimAndDispatch();
        assertThat(dispatcher.awaitIdle(java.time.Duration.ofSeconds(10))).isTrue();
        assertThat(statusOf(waiting)).isNotEqualTo("WAITING");
    }

    // ===================== 도우미 =====================

    private void setLimit(int n) {
        jdbc.sql("UPDATE service_settings SET concurrency_limit = :n").param("n", n).update();
    }

    private int limit() {
        return jdbc.sql("SELECT concurrency_limit FROM service_settings").query(Integer.class).single();
    }

    private String changedBy() {
        return jdbc.sql("SELECT last_changed_by FROM service_settings").query(String.class).optional().orElse(null);
    }

    private Timestamp changedAt() {
        return jdbc.sql("SELECT last_changed_at FROM service_settings").query(Timestamp.class).optional().orElse(null);
    }

    private String statusOf(long ticketId) {
        return jdbc.sql("SELECT status_cd FROM tickets WHERE ticket_id = :id").param("id", ticketId)
                .query(String.class).single();
    }

    /** 학생 1명·과목 1개·WAITING 신청 티켓 1건. 과목을 나눠 "같은 과목 앞 티켓 대기"가 끼지 않게 한다 */
    private long waitingTicket() {
        String[] ids = studentAndCourse();
        return TestFixtures.waitingEnrollmentTicket(jdbc, ids[0], ids[1], ids[2], ids[3]);
    }

    /** 다른 워커가 처리 중인 신청 티켓(CHECK 3: 소유·리스·슬롯 = 세대) */
    private long processingTicket() {
        String[] ids = studentAndCourse();
        return jdbc.sql("""
                        INSERT INTO tickets (request_type, requester_user_id, session_ref, target_course_inst_id,
                                             course_code_snapshot, ticket_issued_at, status_cd, owner_worker_ref,
                                             generation_no, slot_claim_ref, lease_info, processing_started_at)
                        VALUES ('ENROLLMENT', :s, :session, :c, :code, now(), 'PROCESSING', 'test-worker',
                                1, 1, now() + interval '10 minutes', now())
                        RETURNING ticket_id""")
                .param("s", ids[0]).param("session", ids[1]).param("c", ids[2]).param("code", ids[3])
                .query(Long.class).single();
    }

    /** 처리 중 티켓을 종결해 슬롯을 반납한다 */
    private void finish(long ticketId) {
        jdbc.sql("""
                        UPDATE tickets
                           SET status_cd = 'SUCCEEDED', result_cd = 'OK', processing_finished_at = now(),
                               owner_worker_ref = NULL, lease_info = NULL, slot_claim_ref = NULL
                         WHERE ticket_id = :id""")
                .param("id", ticketId).update();
    }

    /** [학생ID, 세션ID, 과목 내부ID, 과목코드] */
    private String[] studentAndCourse() {
        int n = ++seq;
        String studentId = "qss" + UUID.randomUUID().toString().substring(0, 8); // 사용자당 미완료 티켓 1건(INV-5)
        user(studentId, UserRole.STUDENT);
        String sessionId = TestFixtures.session(jdbc, studentId, "STUDENT");
        String code = String.format("QS%04d", n); // 과목코드 10자 이하. 매 테스트 전에 QS 과목을 지운다
        String courseInstId = UUID.randomUUID().toString();
        jdbc.sql("""
                        INSERT INTO courses (course_inst_id, course_code, professor_id, course_title, course_category,
                                             grading_type, max_capacity, min_enrollment, active_cnt, classroom,
                                             course_status, course_created_at)
                        VALUES (:id, :code, :prof, '설정 점검', 'MAJOR', 'RELATIVE', 10, 1, 0, '점검실', 'OPEN', :now)""")
                .param("id", courseInstId).param("code", code).param("prof", PROF)
                .param("now", Timestamp.from(Instant.now())).update();
        return new String[] {studentId, sessionId, courseInstId, code};
    }

    private void awaitTrue(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(50);
        }
        assertThat(condition.getAsBoolean()).as("N 변경 후 10초 안에 claim되어야 한다").isTrue();
    }

    private void user(String id, UserRole role) {
        if (!users.existsById(id)) {
            users.save(UserAccount.builder().userId(id).userName(id)
                    .passwordHash(new BCryptPasswordEncoder(4).encode(PASSWORD)).role(role).build());
        }
    }

    private String bearer(String userId) {
        AuthService.LoginReceipt receipt = auth.login(userId, PASSWORD.toCharArray());
        queue.admitWaitingUsers();
        assertThat(dispatcher.awaitIdle(java.time.Duration.ofSeconds(10))).isTrue();
        return "Bearer " + auth.exchange(Long.parseLong(receipt.ticketId()), receipt.proof()).accessToken();
    }
}
