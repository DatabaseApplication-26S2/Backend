package com.crs.backend.application;

import com.crs.backend.adapter.out.persistence.jpa.EnrollmentJpaRepository;
import com.crs.backend.application.port.CourseLock;
import com.crs.backend.application.port.EnrollmentWriter;
import com.crs.backend.domain.entity.Course;
import com.crs.backend.domain.entity.Enrollment;
import com.crs.backend.domain.enums.EnrollmentStatus;
import com.crs.backend.domain.enums.TicketResultCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * BE-08 수강신청 쓰기. 학점 상한 없음. 결과는 TicketResultCode.
 */
@Service
public class EnrollmentWriterService implements EnrollmentWriter {

    private final CourseLock courseLock;
    private final EnrollmentJpaRepository enrollmentRepository;

    public EnrollmentWriterService(CourseLock courseLock, EnrollmentJpaRepository enrollmentRepository) {
        this.courseLock = courseLock;
        this.enrollmentRepository = enrollmentRepository;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public TicketResultCode enrollStudent(String studentId, String courseInstId, Long sourceTicketId) {
        Course course = courseLock.lockByInstId(courseInstId).orElse(null);
        if (course == null) {
            return TicketResultCode.E04;
        }
        if (!course.isOpen()) {
            return TicketResultCode.E05;
        }
        if (enrollmentRepository.existsByIdStudentIdAndIdCourseInstIdAndEnrollStatus(
                studentId, courseInstId, EnrollmentStatus.ENROLLED)) {
            return TicketResultCode.E06;
        }
        if (!course.hasVacancy()) {
            return TicketResultCode.E07;
        }
        course.increaseActiveCnt();
        enrollmentRepository.saveAndFlush(
                Enrollment.studentEnrollment(studentId, courseInstId, sourceTicketId)
        );
        return TicketResultCode.OK;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public TicketResultCode cancelStudent(String studentId, String courseInstId, Long sourceTicketId) {
        Course course = courseLock.lockByInstId(courseInstId).orElse(null);
        if (course == null) {
            return TicketResultCode.E04;
        }
        if (!course.isOpen()) {
            return TicketResultCode.E05;
        }
        var enrollment = enrollmentRepository.findByIdStudentIdAndIdCourseInstIdAndEnrollStatus(
                studentId, courseInstId, EnrollmentStatus.ENROLLED
        );
        if (enrollment.isEmpty()) {
            return TicketResultCode.E08;
        }
        enrollmentRepository.delete(enrollment.get());
        course.decreaseActiveCnt();
        return TicketResultCode.OK;
    }
}
