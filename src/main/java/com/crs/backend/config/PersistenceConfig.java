package com.crs.backend.config;

import com.crs.backend.adapter.out.persistence.jpa.ServiceSettingsRepository;
import com.crs.backend.domain.entity.ServiceSettings;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * JPA 기반 영속성. ENT-05 서비스 설정 싱글톤 시드.
 */
@Configuration
public class PersistenceConfig {

    @Bean
    ApplicationRunner seedServiceSettings(ServiceSettingsRepository repository) {
        return args -> {
            if (repository.findById(ServiceSettings.SINGLETON_ID).isEmpty()) {
                repository.save(ServiceSettings.initial());
            }
        };
    }
}
