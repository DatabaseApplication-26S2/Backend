package com.project1.enrollment.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * ENT-03 식별 기준: (student_id, course_inst_id)
 */
@Embeddable
@Getter
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EnrollmentId implements Serializable {

    @Column(name = "student_id", length = 20, nullable = false)
    private String studentId;

    @Column(name = "course_inst_id", length = 36, nullable = false)
    private String courseInstId;

    public EnrollmentId(String studentId, String courseInstId) {
        this.studentId = studentId;
        this.courseInstId = courseInstId;
    }
}
