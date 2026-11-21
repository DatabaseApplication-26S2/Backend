package com.crs.backend.queue;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "queue")
public class QueueProperties {

    private String waitingKey = "waiting_queue";
    private int admitBatchSize = 50;
    private long activeTtlMinutes = 5L;
    private long schedulerFixedDelayMs = 1000L;

    /**
     * 동시에 실행하는 티켓 핸들러 수 상한(구현계획 4절 (6)). 핸들러는 트랜잭션 동안 DB 연결을 하나씩 쥐므로
     * {@code spring.datasource.hikari.maximum-pool-size}보다 작게 둔다
     */
    private int workerConcurrency = 16;

    // 3단계 재투입(구현계획 4절 (8), D-07 점검 주기 10초). 스케줄은 TicketReclaimer·TicketLeaseRenewer의 @Scheduled가 같은 키를 읽는다
    private long reclaimFixedDelayMs = 10000L;
    private long leaseRenewFixedDelayMs = 10000L;
    /** 기동 직후 이전 인스턴스가 남긴 PROCESSING 티켓을 회수할지(구현계획 4절 (8) 1) */
    private boolean reclaimOnStartup = true;

    public String getWaitingKey() {
        return waitingKey;
    }

    public void setWaitingKey(String waitingKey) {
        this.waitingKey = waitingKey;
    }

    public int getAdmitBatchSize() {
        return admitBatchSize;
    }

    public void setAdmitBatchSize(int admitBatchSize) {
        this.admitBatchSize = admitBatchSize;
    }

    public long getActiveTtlMinutes() {
        return activeTtlMinutes;
    }

    public void setActiveTtlMinutes(long activeTtlMinutes) {
        this.activeTtlMinutes = activeTtlMinutes;
    }

    public long getSchedulerFixedDelayMs() {
        return schedulerFixedDelayMs;
    }

    public void setSchedulerFixedDelayMs(long schedulerFixedDelayMs) {
        this.schedulerFixedDelayMs = schedulerFixedDelayMs;
    }

    public int getWorkerConcurrency() {
        return workerConcurrency;
    }

    public void setWorkerConcurrency(int workerConcurrency) {
        this.workerConcurrency = workerConcurrency;
    }

    public long getReclaimFixedDelayMs() {
        return reclaimFixedDelayMs;
    }

    public void setReclaimFixedDelayMs(long reclaimFixedDelayMs) {
        this.reclaimFixedDelayMs = reclaimFixedDelayMs;
    }

    public long getLeaseRenewFixedDelayMs() {
        return leaseRenewFixedDelayMs;
    }

    public void setLeaseRenewFixedDelayMs(long leaseRenewFixedDelayMs) {
        this.leaseRenewFixedDelayMs = leaseRenewFixedDelayMs;
    }

    public boolean isReclaimOnStartup() {
        return reclaimOnStartup;
    }

    public void setReclaimOnStartup(boolean reclaimOnStartup) {
        this.reclaimOnStartup = reclaimOnStartup;
    }
}
