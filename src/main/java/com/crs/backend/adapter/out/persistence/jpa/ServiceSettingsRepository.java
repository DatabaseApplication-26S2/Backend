package com.crs.backend.adapter.out.persistence.jpa;

import com.crs.backend.domain.entity.ServiceSettings;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ServiceSettingsRepository extends JpaRepository<ServiceSettings, Boolean> {

    /** 회계 잠금(구현계획 4절 (3)): service_settings 단일 행을 잠근다 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ServiceSettings s where s.singleton = :id")
    Optional<ServiceSettings> findByIdForUpdate(@Param("id") Boolean id);
}
