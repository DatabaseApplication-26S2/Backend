package com.crs.backend.queue;

import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Redis ZSET = 대기 순서 사본. member/score = ticketId. 커밋 뒤에만 ZADD.
 */
@Component
public class RedisWaitingOrderCopy {

    private final RedissonClient redissonClient;
    private final QueueProperties properties;

    public RedisWaitingOrderCopy(RedissonClient redissonClient, QueueProperties properties) {
        this.redissonClient = redissonClient;
        this.properties = properties;
    }

    public void publishAfterCommit(long ticketId) {
        Runnable publish = () -> waitingQueue().add((double) ticketId, String.valueOf(ticketId));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish.run();
                }
            });
        } else {
            publish.run();
        }
    }

    public void removeAfterCommit(long ticketId) {
        Runnable remove = () -> waitingQueue().remove(String.valueOf(ticketId));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    remove.run();
                }
            });
        } else {
            remove.run();
        }
    }

    private RScoredSortedSet<String> waitingQueue() {
        return redissonClient.getScoredSortedSet(properties.getWaitingKey());
    }
}
