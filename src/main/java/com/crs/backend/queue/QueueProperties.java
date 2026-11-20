package com.crs.backend.queue;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "queue")
public class QueueProperties {

    private String waitingKey = "waiting_queue";
    private int admitBatchSize = 50;
    private long activeTtlMinutes = 5L;
    private long schedulerFixedDelayMs = 1000L;

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
