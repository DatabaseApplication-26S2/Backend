package com.crs.backend.queue;

import org.junit.jupiter.api.Test;
import org.redisson.client.protocol.ScoredEntry;
import org.redisson.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RedisWaitingOrderReconcilerTest {
    private final RedissonClient redis = mock(RedissonClient.class);
    @SuppressWarnings("unchecked") private final RScoredSortedSet<String> copy = mock(RScoredSortedSet.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    /** Live Redis state: member -> score. */
    private final Map<String, Double> members = new LinkedHashMap<>();

    RedisWaitingOrderReconcilerTest() {
        when(redis.<String>getScoredSortedSet("waiting_queue")).thenReturn(copy);
        // Snapshot is taken at call time; later mutations of `members` do not leak into it.
        when(copy.entryRange(0, -1)).thenAnswer(call -> {
            var snapshot = new ArrayList<ScoredEntry<String>>();
            members.forEach((member, score) -> snapshot.add(new ScoredEntry<>(score, member)));
            return snapshot;
        });
        when(copy.addAll(anyMap())).thenAnswer(call -> {
            Map<String, Double> added = call.getArgument(0);
            members.putAll(added);
            return added.size();
        });
        when(copy.removeAll(anyCollection())).thenAnswer(call -> members.keySet().removeAll(call.<Collection<?>>getArgument(0)));
    }

    private void reconcile() { new RedisWaitingOrderReconciler(redis, jdbc, new QueueProperties(), true).reconcile(); }

    @Test void redisSnapshotPrecedesDbAndNewlyPublishedMembersAreNotDeleted() {
        members.put("1", 1.0); members.put("2", 2.0);
        when(jdbc.queryForList(anyString(), eq(Long.class))).thenAnswer(call -> {
            members.put("3", 3.0); // A new intake publishes after the Redis snapshot.
            return List.of(2L); // DB snapshot predates that intake commit.
        });
        reconcile();
        var order = inOrder(copy, jdbc);
        order.verify(copy).entryRange(0, -1);
        order.verify(jdbc).queryForList(anyString(), eq(Long.class));
        assertEquals(Set.of("2", "3"), members.keySet());
        verify(copy).removeAll(argThat((Collection<String> c) -> Set.copyOf(c).equals(Set.of("1"))));
        verify(copy, never()).addAll(anyMap());
    }

    @Test void alreadyCorrectCopyIssuesNoWrites() {
        members.put("1", 1.0); members.put("2", 2.0);
        when(jdbc.queryForList(anyString(), eq(Long.class))).thenReturn(List.of(1L, 2L));
        reconcile();
        verify(copy, never()).addAll(anyMap());
        verify(copy, never()).removeAll(anyCollection());
        assertEquals(Set.of("1", "2"), members.keySet());
    }

    @Test void wrongScoreMemberIsReaddedWithScoreEqualToTicketId() {
        members.put("5", -100.0);
        when(jdbc.queryForList(anyString(), eq(Long.class))).thenReturn(List.of(5L));
        reconcile();
        verify(copy).addAll(Map.of("5", 5.0));
        verify(copy, never()).removeAll(anyCollection());
        assertEquals(5.0, members.get("5"));
    }

    @Test void missingMembersAreAddedInOneBatchWithScoreEqualToTicketId() {
        when(jdbc.queryForList(anyString(), eq(Long.class))).thenReturn(List.of(7L, 8L));
        reconcile();
        verify(copy, times(1)).addAll(Map.of("7", 7.0, "8", 8.0));
        verify(copy, never()).removeAll(anyCollection());
    }
}
