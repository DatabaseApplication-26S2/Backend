package com.project1.enrollment.queue;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "queue")
public class QueueProperties {

    private String waitingKey = "waiting_queue";
    private String activeKey = "active_users";
    private int admitBatchSize = 50;
    private long activeTtlMinutes = 5L;
    private long schedulerFixedDelayMs = 1000L;

    public String getWaitingKey() {
        return waitingKey;
    }

    public void setWaitingKey(String waitingKey) {
        this.waitingKey = waitingKey;
    }

    public String getActiveKey() {
        return activeKey;
    }

    public void setActiveKey(String activeKey) {
        this.activeKey = activeKey;
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
}
