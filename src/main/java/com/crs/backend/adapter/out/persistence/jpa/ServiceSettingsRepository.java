package com.crs.backend.adapter.out.persistence.jpa;

import com.crs.backend.domain.entity.ServiceSettings;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ServiceSettingsRepository extends JpaRepository<ServiceSettings, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ServiceSettings s where s.id = :id")
    Optional<ServiceSettings> findByIdForUpdate(@Param("id") Long id);
}
