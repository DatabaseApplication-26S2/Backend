package com.project1.enrollment.domain.policy;

/**
 * 수강신청 도메인 정책 상수.
 * <p>
 * 이수학점·학점 상한은 공식 ERD에서는 범위 외이나,
 * 수강신청 핵심 로직 요구사항에 따라 서비스 계층에서 적용한다.
 */
public final class EnrollmentPolicy {

    /** 학생 최대 신청 가능 학점 */
    public static final int MAX_CREDITS_PER_STUDENT = 18;

    /** 과목 기본 학점 (엔티티에 credit 미설정 시) */
    public static final int DEFAULT_COURSE_CREDITS = 3;

    private EnrollmentPolicy() {
    }
}
