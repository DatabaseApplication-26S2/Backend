package com.project1.enrollment.lock;

public class LockAcquisitionException extends RuntimeException {

    public LockAcquisitionException(String lockKey) {
        super("Failed to acquire distributed lock: " + lockKey);
    }
}
