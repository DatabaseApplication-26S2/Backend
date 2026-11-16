package com.project1.enrollment.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * ENT-05 서비스 설정 (서비스 전체 1건).
 * 물리명: concurrency_limit, last_changed_by, last_changed_at
 */
@Entity
@Table(name = "service_settings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ServiceSettings {

    public static final long SINGLETON_ID = 1L;

    @Id
    @Column(name = "id", nullable = false)
    private Long id = SINGLETON_ID;

    @Column(name = "concurrency_limit", nullable = false)
    private int concurrencyLimit;

    @Column(name = "last_changed_by", length = 20)
    private String lastChangedBy;

    @Column(name = "last_changed_at")
    private Instant lastChangedAt;

    @Builder
    public ServiceSettings(int concurrencyLimit, String lastChangedBy, Instant lastChangedAt) {
        this.id = SINGLETON_ID;
        this.concurrencyLimit = concurrencyLimit;
        this.lastChangedBy = lastChangedBy;
        this.lastChangedAt = lastChangedAt;
    }

    public static ServiceSettings initial() {
        return ServiceSettings.builder()
                .concurrencyLimit(100)
                .build();
    }

    public void updateLimit(int newLimit, String adminUserId) {
        this.concurrencyLimit = newLimit;
        this.lastChangedBy = adminUserId;
        this.lastChangedAt = Instant.now();
    }
}
