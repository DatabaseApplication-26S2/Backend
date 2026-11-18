package com.crs.backend.domain.service;

import com.crs.backend.adapter.out.persistence.jpa.EnrollmentJpaRepository;
import com.crs.backend.application.port.EnrollmentWriter;
import com.crs.backend.domain.entity.Enrollment;
import com.crs.backend.domain.enums.EnrollmentStatus;
import com.crs.backend.domain.enums.TicketResultCode;
import com.crs.backend.domain.exception.EnrollmentException;
import com.crs.backend.domain.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 수강신청 도메인 파사드 (동시성 테스트·직접 호출용).
 * HTTP 신청·취소는 대기열 티켓 → 워커 → EnrollmentWriter 경로를 쓴다.
 */
@Service
@RequiredArgsConstructor
public class EnrollmentService {

    private final EnrollmentWriter enrollmentWriter;
    private final EnrollmentJpaRepository enrollmentRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Enrollment enrollStudent(String studentId, String courseInstId, Long sourceTicketId) {
        TicketResultCode code = enrollmentWriter.enrollStudent(studentId, courseInstId, sourceTicketId);
        if (code != TicketResultCode.OK) {
            throw toException(code);
        }
        return enrollmentRepository
                .findByIdStudentIdAndIdCourseInstIdAndEnrollStatus(
                        studentId, courseInstId, EnrollmentStatus.ENROLLED
                )
                .orElseThrow(() -> new EnrollmentException(ErrorCode.E99, "enrollment missing after OK"));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void cancelStudent(String studentId, String courseInstId, Long sourceTicketId) {
        TicketResultCode code = enrollmentWriter.cancelStudent(studentId, courseInstId, sourceTicketId);
        if (code != TicketResultCode.OK) {
            throw toException(code);
        }
    }

    private EnrollmentException toException(TicketResultCode code) {
        return switch (code) {
            case E04 -> new EnrollmentException(ErrorCode.E04);
            case E05 -> new EnrollmentException(ErrorCode.E05);
            case E06 -> new EnrollmentException(ErrorCode.E06);
            case E07 -> new EnrollmentException(ErrorCode.E07);
            case E08 -> new EnrollmentException(ErrorCode.E08);
            default -> new EnrollmentException(ErrorCode.E99, "Unexpected result: " + code);
        };
    }
}
