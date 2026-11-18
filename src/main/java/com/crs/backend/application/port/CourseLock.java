package com.crs.backend.application.port;

import com.crs.backend.domain.entity.Course;

import java.util.Optional;

/**
 * 과목 행 잠금. Redis 분산 락 대신 DB 행 잠금을 사용한다.
 */
public interface CourseLock {

    Optional<Course> lockByInstId(String courseInstId);
}
