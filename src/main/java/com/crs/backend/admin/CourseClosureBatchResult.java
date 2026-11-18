package com.crs.backend.admin;

import java.util.List;

/** 일괄 폐강 결과(openapi.yaml CourseClosureBatchResult). 입력 순서 그대로 */
public record CourseClosureBatchResult(List<CourseClosureItemResult> items) {
}
