package com.crs.backend.queue;

import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Redis ZSET = 대기 순서 사본. member/score = ticketId. 커밋 뒤에만 ZADD·ZREM.
 * Redis는 사본이므로 쓰기 실패가 이미 커밋된 접수·배정을 실패시키지 않는다(구현계획-backend 4절 (11) 규칙 2).
 */
@Component
public class RedisWaitingOrderCopy {

    private static final Logger log = LoggerFactory.getLogger(RedisWaitingOrderCopy.class);

    private final RedissonClient redissonClient;
    private final QueueProperties properties;

    public RedisWaitingOrderCopy(RedissonClient redissonClient, QueueProperties properties) {
        this.redissonClient = redissonClient;
        this.properties = properties;
    }

    public void publishAfterCommit(long ticketId) {
        afterCommit(() -> waitingQueue().add((double) ticketId, String.valueOf(ticketId)), "publish", ticketId);
    }

    public void removeAfterCommit(long ticketId) {
        afterCommit(() -> waitingQueue().remove(String.valueOf(ticketId)), "removal", ticketId);
    }

    private void afterCommit(Runnable mirrorWrite, String action, long ticketId) {
        Runnable safe = () -> {
            try {
                mirrorWrite.run();
            } catch (RuntimeException ex) {
                log.warn("Queue mirror {} failed for ticket {}", action, ticketId);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    safe.run();
                }
            });
        } else {
            safe.run();
        }
    }

    private RScoredSortedSet<String> waitingQueue() {
        return redissonClient.getScoredSortedSet(properties.getWaitingKey());
    }
}
