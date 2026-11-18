package com.crs.backend.adapter.out.persistence;

import com.crs.backend.adapter.out.persistence.jpa.CourseJpaRepository;
import com.crs.backend.application.port.CourseLock;
import com.crs.backend.domain.entity.Course;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class JpaCourseLock implements CourseLock {

    private final CourseJpaRepository courseRepository;

    public JpaCourseLock(CourseJpaRepository courseRepository) {
        this.courseRepository = courseRepository;
    }

    @Override
    public Optional<Course> lockByInstId(String courseInstId) {
        return courseRepository.findByIdForUpdate(courseInstId);
    }
}
