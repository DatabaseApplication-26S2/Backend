package com.project1.enrollment.adapter.out.persistence.jpa;

import com.project1.enrollment.domain.entity.Course;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface CourseJpaRepository extends JpaRepository<Course, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Course c where c.courseInstId = :id")
    Optional<Course> findByIdForUpdate(@Param("id") String id);
    Optional<Course> findByCourseCode(String code);
    List<Course> findByCourseInstIdIn(Collection<String> ids);
}
