package com.crs.backend.application.port;

import com.crs.backend.domain.entity.Course;

import java.util.Optional;

/**
 * 과목 행 잠금. 같은 과목의 쓰기는 DB 행 잠금으로 직렬화한다.
 */
public interface CourseLock {

    Optional<Course> lockByInstId(String courseInstId);
}
