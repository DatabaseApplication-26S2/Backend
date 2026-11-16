package com.crs.backend.lock;

public class LockAcquisitionException extends RuntimeException {

    public LockAcquisitionException(String lockKey) {
        super("Failed to acquire distributed lock: " + lockKey);
    }
}
