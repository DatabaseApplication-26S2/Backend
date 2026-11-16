package com.project1.enrollment.domain.service;

import com.project1.enrollment.adapter.out.persistence.jpa.CourseJpaRepository;
import com.project1.enrollment.adapter.out.persistence.jpa.EnrollmentJpaRepository;
import com.project1.enrollment.adapter.out.persistence.jpa.WaitingTicketJpaRepository;
import com.project1.enrollment.domain.entity.Course;
import com.project1.enrollment.domain.entity.Enrollment;
import com.project1.enrollment.domain.entity.EnrollmentId;
import com.project1.enrollment.domain.enums.CourseCategory;
import com.project1.enrollment.domain.enums.CourseStatus;
import com.project1.enrollment.domain.enums.EnrollmentStatus;
import com.project1.enrollment.domain.enums.EnrollmentType;
import com.project1.enrollment.domain.enums.EvaluationType;
import com.project1.enrollment.domain.exception.EnrollmentException;
import com.project1.enrollment.domain.exception.ErrorCode;
import com.project1.enrollment.domain.policy.EnrollmentPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EnrollmentServiceTest {

    @Mock
    private CourseJpaRepository courseRepository;
    @Mock
    private EnrollmentJpaRepository enrollmentRepository;
    @Mock
    private WaitingTicketJpaRepository waitingTicketRepository;

    private EnrollmentService enrollmentService;
    private Course course;

    @BeforeEach
    void setUp() {
        enrollmentService = new EnrollmentService(courseRepository, enrollmentRepository, waitingTicketRepository);
        course = openCourse("CSE3010", 30, 0, 3);
    }

    @Test
    void enroll_success_increases_active_cnt() {
        when(courseRepository.findByIdForUpdate(course.getCourseInstId())).thenReturn(Optional.of(course));
        when(enrollmentRepository.existsByIdStudentIdAndIdCourseInstIdAndEnrollStatus(
                "20240001", course.getCourseInstId(), EnrollmentStatus.ENROLLED)).thenReturn(false);
        when(enrollmentRepository.findByIdStudentIdAndEnrollStatusOrderByIdCourseInstIdAsc(
                "20240001", EnrollmentStatus.ENROLLED)).thenReturn(List.of());
        when(courseRepository.findByCourseInstIdIn(anyCollection())).thenReturn(List.of());
        when(enrollmentRepository.saveAndFlush(any(Enrollment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(waitingTicketRepository.findById(1L)).thenReturn(Optional.empty());

        Enrollment saved = enrollmentService.enrollStudent("20240001", course.getCourseInstId(), 1L);

        assertEquals(1, course.getActiveCnt());
        assertEquals(EnrollmentStatus.ENROLLED, saved.getEnrollStatus());
        verify(courseRepository).saveAndFlush(course);
    }

    @Test
    void enroll_fails_when_already_enrolled() {
        when(courseRepository.findByIdForUpdate(course.getCourseInstId())).thenReturn(Optional.of(course));
        when(enrollmentRepository.existsByIdStudentIdAndIdCourseInstIdAndEnrollStatus(
                "20240001", course.getCourseInstId(), EnrollmentStatus.ENROLLED)).thenReturn(true);
        when(waitingTicketRepository.findById(1L)).thenReturn(Optional.empty());

        EnrollmentException ex = assertThrows(
                EnrollmentException.class,
                () -> enrollmentService.enrollStudent("20240001", course.getCourseInstId(), 1L)
        );
        assertEquals(ErrorCode.E06, ex.getErrorCode());
    }

    @Test
    void enroll_fails_when_credit_limit_exceeded() {
        Course heavy = openCourse("CSE9999", 30, 0, 16);
        when(courseRepository.findByIdForUpdate(heavy.getCourseInstId())).thenReturn(Optional.of(heavy));
        when(enrollmentRepository.existsByIdStudentIdAndIdCourseInstIdAndEnrollStatus(
                "20240001", heavy.getCourseInstId(), EnrollmentStatus.ENROLLED)).thenReturn(false);

        Course already = openCourse("CSE1000", 30, 1, 3);
        Enrollment existing = Enrollment.builder()
                .id(new EnrollmentId("20240001", already.getCourseInstId()))
                .enrollStatus(EnrollmentStatus.ENROLLED)
                .enrollType(EnrollmentType.STUDENT)
                .enrolledAt(Instant.now())
                .build();
        when(enrollmentRepository.findByIdStudentIdAndEnrollStatusOrderByIdCourseInstIdAsc(
                "20240001", EnrollmentStatus.ENROLLED)).thenReturn(List.of(existing));
        when(courseRepository.findByCourseInstIdIn(anyCollection())).thenReturn(List.of(already));

        EnrollmentException ex = assertThrows(
                EnrollmentException.class,
                () -> enrollmentService.enrollStudent("20240001", heavy.getCourseInstId(), null)
        );
        assertEquals(ErrorCode.CREDIT_LIMIT_EXCEEDED, ex.getErrorCode());
        assertEquals(EnrollmentPolicy.MAX_CREDITS_PER_STUDENT, 18);
    }

    @Test
    void enroll_fails_when_no_vacancy() {
        Course full = openCourse("CSE3010", 1, 1, 3);
        when(courseRepository.findByIdForUpdate(full.getCourseInstId())).thenReturn(Optional.of(full));
        when(enrollmentRepository.existsByIdStudentIdAndIdCourseInstIdAndEnrollStatus(
                eq("20240001"), eq(full.getCourseInstId()), eq(EnrollmentStatus.ENROLLED))).thenReturn(false);
        when(enrollmentRepository.findByIdStudentIdAndEnrollStatusOrderByIdCourseInstIdAsc(
                "20240001", EnrollmentStatus.ENROLLED)).thenReturn(List.of());
        when(courseRepository.findByCourseInstIdIn(anyCollection())).thenReturn(List.of());
        when(waitingTicketRepository.findById(1L)).thenReturn(Optional.empty());

        EnrollmentException ex = assertThrows(
                EnrollmentException.class,
                () -> enrollmentService.enrollStudent("20240001", full.getCourseInstId(), 1L)
        );
        assertEquals(ErrorCode.E07, ex.getErrorCode());
    }

    private Course openCourse(String code, int capacity, int activeCnt, int credit) {
        return Course.builder()
                .courseInstId(UUID.randomUUID().toString())
                .courseCode(code)
                .professorId("P001")
                .courseTitle(code)
                .courseCategory(CourseCategory.MAJOR)
                .gradingType(EvaluationType.ABSOLUTE)
                .maxCapacity(capacity)
                .minEnrollment(1)
                .credit(credit)
                .activeCnt(activeCnt)
                .classroom("공학관 301")
                .courseStatus(CourseStatus.OPEN)
                .courseCreatedAt(Instant.now())
                .build();
    }
}
