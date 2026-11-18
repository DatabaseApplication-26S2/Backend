package com.crs.backend.course;

import java.util.List;

/** 학생 본인의 현재 신청 과목 전체(openapi.yaml MyEnrollmentList). 페이지 없음, 과목코드 오름차순 */
public record MyEnrollmentList(List<MyEnrollmentView> items) {
}
