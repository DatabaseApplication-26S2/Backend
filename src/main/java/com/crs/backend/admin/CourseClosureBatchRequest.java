package com.crs.backend.admin;

import java.util.List;

/** 일괄 폐강 본문(openapi.yaml CourseClosureBatchRequest). 1개 이상, 중복 없음, 처리와 결과는 이 순서 */
public record CourseClosureBatchRequest(List<String> courseCodes) {
}
