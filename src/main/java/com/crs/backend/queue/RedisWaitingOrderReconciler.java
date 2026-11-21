package com.crs.backend.queue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.protocol.ScoredEntry;
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
            Map<String, Double> observed = new HashMap<>();
            for (ScoredEntry<String> entry : copy.entryRange(0, -1)) observed.put(entry.getValue(), entry.getScore());
            List<Long> waiting = jdbc.queryForList("SELECT ticket_id FROM tickets WHERE status_cd='WAITING'", Long.class);
            Map<String, Double> upserts = new HashMap<>();
            for (long id : waiting) {
                String member = Long.toString(id);
                Double score = observed.remove(member);
                // Refresh the score too: late/wrong-score writes are harmless after the next pass.
                if (score == null || score.doubleValue() != id) upserts.put(member, (double) id);
            }
            // What remains was in the Redis snapshot but is no longer WAITING in the DB.
            if (!upserts.isEmpty()) copy.addAll(upserts);
            if (!observed.isEmpty()) copy.removeAll(observed.keySet());
        } catch (RuntimeException ex) {
            LoggerFactory.getLogger(getClass()).warn("Waiting-order reconciliation failed ({}); will retry", ex.getClass().getSimpleName());
        }
    }
}
