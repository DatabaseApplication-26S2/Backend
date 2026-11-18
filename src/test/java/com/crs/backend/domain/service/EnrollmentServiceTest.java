package com.crs.backend.domain.service;

import com.crs.backend.adapter.out.persistence.jpa.EnrollmentJpaRepository;
import com.crs.backend.application.EnrollmentWriterService;
import com.crs.backend.application.port.CourseLock;
import com.crs.backend.domain.entity.Course;
import com.crs.backend.domain.entity.Enrollment;
import com.crs.backend.domain.enums.CourseCategory;
import com.crs.backend.domain.enums.CourseStatus;
import com.crs.backend.domain.enums.EnrollmentStatus;
import com.crs.backend.domain.enums.EvaluationType;
import com.crs.backend.domain.exception.EnrollmentException;
import com.crs.backend.domain.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EnrollmentServiceTest {

    @Mock
    private CourseLock courseLock;
    @Mock
    private EnrollmentJpaRepository enrollmentRepository;

    private EnrollmentService enrollmentService;
    private Course course;

    @BeforeEach
    void setUp() {
        EnrollmentWriterService writer = new EnrollmentWriterService(courseLock, enrollmentRepository);
        enrollmentService = new EnrollmentService(writer, enrollmentRepository);
        course = openCourse("CSE3010", 30, 0);
    }

    @Test
    void enroll_success_increases_active_cnt() {
        when(courseLock.lockByInstId(course.getCourseInstId())).thenReturn(Optional.of(course));
        when(enrollmentRepository.existsByIdStudentIdAndIdCourseInstIdAndEnrollStatus(
                "20240001", course.getCourseInstId(), EnrollmentStatus.ENROLLED)).thenReturn(false);
        when(enrollmentRepository.saveAndFlush(any(Enrollment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(enrollmentRepository.findByIdStudentIdAndIdCourseInstIdAndEnrollStatus(
                "20240001", course.getCourseInstId(), EnrollmentStatus.ENROLLED))
                .thenAnswer(inv -> Optional.of(Enrollment.studentEnrollment(
                        "20240001", course.getCourseInstId(), 1L)));

        Enrollment saved = enrollmentService.enrollStudent("20240001", course.getCourseInstId(), 1L);

        assertEquals(1, course.getActiveCnt());
        assertEquals(EnrollmentStatus.ENROLLED, saved.getEnrollStatus());
    }

    @Test
    void enroll_fails_when_already_enrolled() {
        when(courseLock.lockByInstId(course.getCourseInstId())).thenReturn(Optional.of(course));
        when(enrollmentRepository.existsByIdStudentIdAndIdCourseInstIdAndEnrollStatus(
                "20240001", course.getCourseInstId(), EnrollmentStatus.ENROLLED)).thenReturn(true);

        EnrollmentException ex = assertThrows(
                EnrollmentException.class,
                () -> enrollmentService.enrollStudent("20240001", course.getCourseInstId(), 1L)
        );
        assertEquals(ErrorCode.E06, ex.getErrorCode());
    }

    @Test
    void enroll_fails_when_no_vacancy() {
        Course full = openCourse("CSE3010", 1, 1);
        when(courseLock.lockByInstId(full.getCourseInstId())).thenReturn(Optional.of(full));
        when(enrollmentRepository.existsByIdStudentIdAndIdCourseInstIdAndEnrollStatus(
                eq("20240001"), eq(full.getCourseInstId()), eq(EnrollmentStatus.ENROLLED))).thenReturn(false);

        EnrollmentException ex = assertThrows(
                EnrollmentException.class,
                () -> enrollmentService.enrollStudent("20240001", full.getCourseInstId(), 1L)
        );
        assertEquals(ErrorCode.E07, ex.getErrorCode());
    }

    private Course openCourse(String code, int capacity, int activeCnt) {
        return Course.builder()
                .courseInstId(UUID.randomUUID().toString())
                .courseCode(code)
                .professorId("P001")
                .courseTitle(code)
                .courseCategory(CourseCategory.MAJOR)
                .gradingType(EvaluationType.ABSOLUTE)
                .maxCapacity(capacity)
                .minEnrollment(1)
                .activeCnt(activeCnt)
                .classroom("공학관 301")
                .courseStatus(CourseStatus.OPEN)
                .courseCreatedAt(Instant.now())
                .build();
    }
}
