package com.crs.backend.adapter.out.persistence.jpa;
import com.crs.backend.domain.entity.AccessSession;
import org.springframework.data.jpa.repository.JpaRepository;
public interface AccessSessionRepository extends JpaRepository<AccessSession, String> {}
