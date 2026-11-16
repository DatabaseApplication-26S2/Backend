package com.crs.backend.lock;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "distributed-lock")
public class DistributedLockProperties {

    /**
     * 락 대기 시간(초).
     */
    private long waitTime = 5L;

    /**
     * 락 임대 시간(초). 트랜잭션 커밋 후 AOP가 명시적으로 unlock 한다.
     */
    private long leaseTime = 10L;

    public long getWaitTime() {
        return waitTime;
    }

    public void setWaitTime(long waitTime) {
        this.waitTime = waitTime;
    }

    public long getLeaseTime() {
        return leaseTime;
    }

    public void setLeaseTime(long leaseTime) {
        this.leaseTime = leaseTime;
    }
}
