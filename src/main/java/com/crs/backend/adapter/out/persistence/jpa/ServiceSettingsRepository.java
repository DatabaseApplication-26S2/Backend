package com.crs.backend.adapter.out.persistence.jpa;
import com.crs.backend.domain.entity.ServiceSettings;
import org.springframework.data.jpa.repository.JpaRepository;
public interface ServiceSettingsRepository extends JpaRepository<ServiceSettings, Boolean> {}
