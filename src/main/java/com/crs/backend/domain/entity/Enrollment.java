package com.crs.backend.domain.entity;

import com.crs.backend.domain.enums.EnrollmentStatus;
import com.crs.backend.domain.enums.EnrollmentType;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * ENT-03 수강신청 내역.
 * 물리명: student_id, course_inst_id, enroll_status, enroll_type, enrolled_at,
 * assigned_by_user_id, source_ticket_id
 */
@Entity
@Table(name = "enrollments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Enrollment {

    @EmbeddedId
    private EnrollmentId id;

    @Enumerated(EnumType.STRING)
    @Column(name = "enroll_status", length = 20, nullable = false)
    private EnrollmentStatus enrollStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "enroll_type", nullable = false)
    private EnrollmentType enrollType;

    @Column(name = "enrolled_at", nullable = false)
    private Instant enrolledAt;

    @Column(name = "assigned_by_user_id", length = 20)
    private String assignedByUserId;

    @Column(name = "source_ticket_id")
    private Long sourceTicketId;

    @Builder
    public Enrollment(
            EnrollmentId id,
            EnrollmentStatus enrollStatus,
            EnrollmentType enrollType,
            Instant enrolledAt,
            String assignedByUserId,
            Long sourceTicketId
    ) {
        this.id = id;
        this.enrollStatus = enrollStatus;
        this.enrollType = enrollType;
        this.enrolledAt = enrolledAt;
        this.assignedByUserId = assignedByUserId;
        this.sourceTicketId = sourceTicketId;
    }

    public static Enrollment studentEnrollment(String studentId, String courseInstId, Long sourceTicketId) {
        return Enrollment.builder()
                .id(new EnrollmentId(studentId, courseInstId))
                .enrollStatus(EnrollmentStatus.ENROLLED)
                .enrollType(EnrollmentType.STUDENT)
                .enrolledAt(Instant.now())
                .sourceTicketId(sourceTicketId)
                .build();
    }

    public void markClosed() {
        this.enrollStatus = EnrollmentStatus.CLOSED;
    }

    public boolean isEnrolled() {
        return enrollStatus == EnrollmentStatus.ENROLLED;
    }
}
