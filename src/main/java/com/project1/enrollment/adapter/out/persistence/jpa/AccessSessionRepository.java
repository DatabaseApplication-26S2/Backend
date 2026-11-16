package com.project1.enrollment.adapter.out.persistence.jpa;
import com.project1.enrollment.domain.entity.AccessSession;
import org.springframework.data.jpa.repository.JpaRepository;
public interface AccessSessionRepository extends JpaRepository<AccessSession, String> {}
