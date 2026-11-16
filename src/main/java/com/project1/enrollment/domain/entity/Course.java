package com.project1.enrollment.domain.entity;

import com.project1.enrollment.domain.enums.CourseCategory;
import com.project1.enrollment.domain.enums.CourseStatus;
import com.project1.enrollment.domain.enums.EvaluationType;
import com.project1.enrollment.domain.exception.EnrollmentException;
import com.project1.enrollment.domain.exception.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * ENT-02 과목.
 * 물리명: course_inst_id, course_code, professor_id, course_title, course_category,
 * grading_type, max_capacity, min_enrollment, active_cnt, classroom, course_status,
 * course_created_at, course_updated_at
 */
@Entity
@Table(
        name = "courses",
        uniqueConstraints = @UniqueConstraint(name = "uk_course_code", columnNames = "course_code")
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Course {

    @Id
    @Column(name = "course_inst_id", length = 36, nullable = false)
    private String courseInstId;

    @Column(name = "course_code", length = 10, nullable = false)
    private String courseCode;

    @Column(name = "professor_id", length = 20, nullable = false)
    private String professorId;

    @Column(name = "course_title", length = 100, nullable = false)
    private String courseTitle;

    @Enumerated(EnumType.STRING)
    @Column(name = "course_category", nullable = false)
    private CourseCategory courseCategory;

    @Enumerated(EnumType.STRING)
    @Column(name = "grading_type", nullable = false)
    private EvaluationType gradingType;

    @Column(name = "max_capacity", nullable = false)
    private int maxCapacity;

    @Column(name = "min_enrollment", nullable = false)
    private int minEnrollment;

    /**
     * 이수학점. 공식 ERD 범위 외 항목이나 수강신청 학점 상한 검증을 위해 유지한다.
     * 기본값 3.
     */
    @Column(name = "credit", nullable = false)
    private int credit;

    /** 저장 파생값: ENROLLED 내역 수와 동일 (INV-2) */
    @Column(name = "active_cnt", nullable = false)
    private int activeCnt;

    @Column(name = "classroom", nullable = false)
    private String classroom;

    @Enumerated(EnumType.STRING)
    @Column(name = "course_status", length = 20, nullable = false)
    private CourseStatus courseStatus;

    @Column(name = "course_created_at", nullable = false)
    private Instant courseCreatedAt;

    @Column(name = "course_updated_at")
    private Instant courseUpdatedAt;

    @Builder
    public Course(
            String courseInstId,
            String courseCode,
            String professorId,
            String courseTitle,
            CourseCategory courseCategory,
            EvaluationType gradingType,
            int maxCapacity,
            int minEnrollment,
            int credit,
            int activeCnt,
            String classroom,
            CourseStatus courseStatus,
            Instant courseCreatedAt,
            Instant courseUpdatedAt
    ) {
        this.courseInstId = courseInstId;
        this.courseCode = courseCode;
        this.professorId = professorId;
        this.courseTitle = courseTitle;
        this.courseCategory = courseCategory;
        this.gradingType = gradingType;
        this.maxCapacity = maxCapacity;
        this.minEnrollment = minEnrollment;
        this.credit = credit > 0 ? credit : 3;
        this.activeCnt = activeCnt;
        this.classroom = classroom;
        this.courseStatus = courseStatus;
        this.courseCreatedAt = courseCreatedAt;
        this.courseUpdatedAt = courseUpdatedAt;
    }

    public boolean isOpen() {
        return courseStatus == CourseStatus.OPEN;
    }

    public boolean hasVacancy() {
        return activeCnt < maxCapacity;
    }

    public int remainingSeats() {
        return Math.max(maxCapacity - activeCnt, 0);
    }

    public void increaseActiveCnt() {
        if (!isOpen()) {
            throw new EnrollmentException(ErrorCode.E05, "Course is closed: " + courseCode);
        }
        if (!hasVacancy()) {
            throw new EnrollmentException(ErrorCode.E07, "No remaining seats: " + courseCode);
        }
        this.activeCnt++;
    }

    public void decreaseActiveCnt() {
        if (this.activeCnt <= 0) {
            throw new EnrollmentException(ErrorCode.E08, "active_cnt cannot be negative: " + courseCode);
        }
        this.activeCnt--;
    }

    public void close() {
        this.courseStatus = CourseStatus.CLOSED;
        this.activeCnt = 0;
    }
}
