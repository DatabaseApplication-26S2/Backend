package com.crs.backend.queue;

import java.util.HashSet;
import java.util.List;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** DB is authoritative. A failed Redis pass is retried next period and cannot alter ticket state. */
@Component
public class RedisWaitingOrderReconciler {
    private final RedissonClient redis;
    private final JdbcTemplate jdbc;
    private final QueueProperties properties;
    private final boolean enabled;
    public RedisWaitingOrderReconciler(RedissonClient redis, JdbcTemplate jdbc, QueueProperties properties,
            @Value("${queue.reconcile-enabled:true}") boolean enabled) {
        this.redis = redis; this.jdbc = jdbc; this.properties = properties; this.enabled = enabled;
    }
    @EventListener(ApplicationReadyEvent.class)
    public synchronized void rebuildOnStartup() {
        if (!enabled) return;
        runSafely(true);
    }
    @Scheduled(initialDelayString = "${queue.reconcile-fixed-delay-ms:10000}",
               fixedDelayString = "${queue.reconcile-fixed-delay-ms:10000}")
    public synchronized void reconcile() {
        if (!enabled) return;
        runSafely(false);
    }
    private void runSafely(boolean rebuild) {
        try {
            RScoredSortedSet<String> copy = redis.getScoredSortedSet(properties.getWaitingKey());
            if (rebuild) copy.delete();
            // Redis snapshot must precede the DB snapshot. Do not delete members published after this read.
            var observed = new HashSet<>(copy.readAll());
            List<Long> waiting = jdbc.queryForList("SELECT ticket_id FROM tickets WHERE status_cd='WAITING' ORDER BY ticket_id", Long.class);
            var expected = new HashSet<String>();
            for (long id : waiting) {
                String member = Long.toString(id);
                expected.add(member);
                // Refresh the score too: late/wrong-score writes are harmless after the next pass.
                copy.add((double) id, member);
            }
            observed.removeAll(expected);
            for (String stale : observed) copy.remove(stale);
        } catch (RuntimeException ex) {
            LoggerFactory.getLogger(getClass()).warn("Waiting-order reconciliation failed ({}); will retry", ex.getClass().getSimpleName());
        }
    }
}
