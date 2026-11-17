package com.crs.backend.domain.entity;

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
 * 물리명: singleton, concurrency_limit, last_changed_by, last_changed_at
 * singleton(boolean, 항상 true)은 단일 행을 강제하는 물리 장치다(구현계획 3.5). 초기 행은 V1이 넣는다.
 */
@Entity
@Table(name = "service_settings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ServiceSettings {

    public static final Boolean SINGLETON_ID = Boolean.TRUE;

    @Id
    @Column(name = "singleton", nullable = false, columnDefinition = "bool")
    private Boolean singleton = SINGLETON_ID;

    @Column(name = "concurrency_limit", nullable = false)
    private int concurrencyLimit;

    @Column(name = "last_changed_by", length = 20)
    private String lastChangedBy;

    @Column(name = "last_changed_at")
    private Instant lastChangedAt;

    @Builder
    public ServiceSettings(int concurrencyLimit, String lastChangedBy, Instant lastChangedAt) {
        this.singleton = SINGLETON_ID;
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
