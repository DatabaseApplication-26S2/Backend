package com.crs.backend.domain.service;

import com.crs.backend.TestFixtures;
import com.crs.backend.TestcontainersConfiguration;
import com.crs.backend.adapter.out.persistence.jpa.CourseJpaRepository;
import com.crs.backend.adapter.out.persistence.jpa.EnrollmentJpaRepository;
import com.crs.backend.domain.entity.Course;
import com.crs.backend.domain.entity.UserAccount;
import com.crs.backend.domain.enums.CourseCategory;
import com.crs.backend.domain.enums.CourseStatus;
import com.crs.backend.domain.enums.EnrollmentStatus;
import com.crs.backend.domain.enums.EvaluationType;
import com.crs.backend.domain.enums.UserRole;
import com.crs.backend.domain.exception.EnrollmentException;
import com.crs.backend.domain.exception.ErrorCode;
import com.crs.backend.adapter.out.persistence.jpa.UserAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.task.scheduling.enabled=false",
        "queue.scheduler-fixed-delay-ms=3600000",
        "distributed-lock.wait-time=60",
        "distributed-lock.lease-time=30",
        "spring.autoconfigure.exclude=org.redisson.spring.starter.RedissonAutoConfigurationV2,org.redisson.spring.starter.RedissonAutoConfigurationV4"
})
@ActiveProfiles("concurrency-test")
@Import({ConcurrencyTestConfig.class, TestcontainersConfiguration.class})
class EnrollmentConcurrencyTest {

    private static final int CAPACITY = 30;
    private static final int TOTAL_STUDENTS = 100;

    @Autowired
    private EnrollmentService enrollmentService;
    @Autowired
    private CourseJpaRepository courseRepository;
    @Autowired
    private EnrollmentJpaRepository enrollmentRepository;
    @Autowired
    private UserAccountRepository userAccountRepository;
    @Autowired
    private JdbcClient jdbc;

    private String courseInstId;
    private final List<String> studentIds = new ArrayList<>();
    /** 학생별 근거 티켓(enrollments의 복합 FK·CHECK: STUDENT 내역은 같은 학생·같은 과목의 티켓이 있어야 함) */
    private final Map<String, Long> ticketIds = new HashMap<>();

    @BeforeEach
    void setUp() {
        // FK 순서: 내역·리프레시 → 세션·티켓(서로 참조하므로 한 문장으로) → 과목 → 사용자
        jdbc.sql("TRUNCATE enrollments, refresh_tokens, sessions, tickets, courses").update();
        userAccountRepository.deleteAll();
        studentIds.clear();
        ticketIds.clear();

        // courses.professor_id → users FK
        userAccountRepository.save(UserAccount.builder()
                .userId("P001")
                .passwordHash("hash")
                .userName("Professor")
                .role(UserRole.PROFESSOR)
                .build());

        Course course = courseRepository.save(Course.builder()
                .courseInstId(UUID.randomUUID().toString())
                .courseCode("CS100")
                .professorId("P001")
                .courseTitle("Concurrency Lab")
                .courseCategory(CourseCategory.MAJOR)
                .gradingType(EvaluationType.ABSOLUTE)
                .maxCapacity(CAPACITY)
                .minEnrollment(1)
                .activeCnt(0)
                .classroom("Lab-1")
                .courseStatus(CourseStatus.OPEN)
                .courseCreatedAt(Instant.now())
                .build());
        courseInstId = course.getCourseInstId();

        for (int i = 1; i <= TOTAL_STUDENTS; i++) {
            String studentId = "2024%04d".formatted(i);
            userAccountRepository.save(UserAccount.builder()
                    .userId(studentId)
                    .passwordHash("hash")
                    .userName("Student-" + i)
                    .role(UserRole.STUDENT)
                    .build());
            studentIds.add(studentId);
            String sessionId = TestFixtures.session(jdbc, studentId, "STUDENT");
            ticketIds.put(studentId,
                    TestFixtures.waitingEnrollmentTicket(jdbc, studentId, sessionId, courseInstId, "CS100"));
        }
    }

    @Test
    void concurrent_enroll_should_accept_exactly_capacity() throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(TOTAL_STUDENTS);
        CountDownLatch readyLatch = new CountDownLatch(TOTAL_STUDENTS);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(TOTAL_STUDENTS);

        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger courseFullCount = new AtomicInteger();

        for (String studentId : studentIds) {
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    enrollmentService.enrollStudent(studentId, courseInstId, ticketIds.get(studentId));
                    successCount.incrementAndGet();
                } catch (EnrollmentException ex) {
                    if (ex.getErrorCode() == ErrorCode.E07) {
                        courseFullCount.incrementAndGet();
                    } else {
                        throw ex;
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ex);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        assertThat(readyLatch.await(10, TimeUnit.SECONDS)).isTrue();
        startLatch.countDown();
        assertThat(doneLatch.await(90, TimeUnit.SECONDS)).isTrue();
        executor.shutdownNow();

        Course saved = courseRepository.findById(courseInstId).orElseThrow();
        assertThat(saved.getActiveCnt()).isEqualTo(CAPACITY);
        assertThat(enrollmentRepository.countByIdCourseInstIdAndEnrollStatus(
                courseInstId, EnrollmentStatus.ENROLLED)).isEqualTo(CAPACITY);
        assertThat(successCount.get()).isEqualTo(CAPACITY);
        assertThat(courseFullCount.get()).isEqualTo(TOTAL_STUDENTS - CAPACITY);
    }
}
