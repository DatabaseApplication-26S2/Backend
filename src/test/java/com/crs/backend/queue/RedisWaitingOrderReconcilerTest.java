package com.crs.backend.queue;

import org.junit.jupiter.api.Test;
import org.redisson.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RedisWaitingOrderReconcilerTest {
    @Test void redisSnapshotPrecedesDbAndNewlyPublishedMembersAreNotDeleted() {
        var redis = mock(RedissonClient.class);
        @SuppressWarnings("unchecked") RScoredSortedSet<String> copy = mock(RScoredSortedSet.class);
        var jdbc = mock(JdbcTemplate.class);
        when(redis.<String>getScoredSortedSet("waiting_queue")).thenReturn(copy);
        var members = new HashSet<>(Set.of("1", "2"));
        when(copy.readAll()).thenAnswer(call -> new ArrayList<>(members));
        when(jdbc.queryForList(anyString(), eq(Long.class))).thenAnswer(call -> {
            members.add("3"); // A new intake publishes after the Redis snapshot.
            return List.of(2L); // DB snapshot predates that intake commit.
        });
        when(copy.remove(anyString())).thenAnswer(call -> members.remove(call.getArgument(0)));
        new RedisWaitingOrderReconciler(redis, jdbc, new QueueProperties(), true).reconcile();
        var order = inOrder(copy, jdbc);
        order.verify(copy).readAll();
        order.verify(jdbc).queryForList(anyString(), eq(Long.class));
        assertEquals(Set.of("2", "3"), members);
        verify(copy, never()).remove("3");
    }
}
