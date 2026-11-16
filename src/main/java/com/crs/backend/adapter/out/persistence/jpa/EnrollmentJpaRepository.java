package com.crs.backend.adapter.out.persistence.jpa;

import com.crs.backend.domain.entity.*;
import com.crs.backend.domain.enums.EnrollmentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface EnrollmentJpaRepository extends JpaRepository<Enrollment, EnrollmentId> {
    long countByIdCourseInstIdAndEnrollStatus(String course, EnrollmentStatus status);
    boolean existsByIdStudentIdAndIdCourseInstIdAndEnrollStatus(String student, String course, EnrollmentStatus status);
    Optional<Enrollment> findByIdStudentIdAndIdCourseInstIdAndEnrollStatus(String student, String course, EnrollmentStatus status);
    List<Enrollment> findByIdStudentIdAndEnrollStatusOrderByIdCourseInstIdAsc(String student, EnrollmentStatus status);
}
