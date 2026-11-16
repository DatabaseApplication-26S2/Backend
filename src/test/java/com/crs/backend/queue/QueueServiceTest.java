package com.crs.backend.queue;

import com.crs.backend.adapter.in.web.dto.TicketReceipt;
import com.crs.backend.adapter.out.persistence.jpa.AccessSessionRepository;
import com.crs.backend.adapter.out.persistence.jpa.WaitingTicketJpaRepository;
import com.crs.backend.domain.entity.WaitingTicket;
import com.crs.backend.domain.enums.TicketRequestType;
import com.crs.backend.domain.enums.TicketStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RSetCache;
import org.redisson.api.RedissonClient;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QueueServiceTest {

    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RScoredSortedSet<String> waitingQueue;
    @Mock
    private RSetCache<String> activeUsers;
    @Mock
    private WaitingTicketJpaRepository waitingTicketRepository;
    @Mock
    private AccessSessionRepository accessSessionRepository;

    private QueueService queueService;

    @BeforeEach
    void setUp() {
        QueueProperties properties = new QueueProperties();
        properties.setWaitingKey("waiting_queue");
        properties.setActiveKey("active_users");
        properties.setAdmitBatchSize(50);
        properties.setActiveTtlMinutes(5L);

        queueService = new QueueService(
                redissonClient, properties, waitingTicketRepository, accessSessionRepository
        );

        lenient().when(redissonClient.<String>getScoredSortedSet("waiting_queue")).thenReturn(waitingQueue);
        lenient().when(redissonClient.<String>getSetCache("active_users")).thenReturn(activeUsers);
    }

    @Test
    void requestAdmission_adds_ticket_id_to_zset_with_timestamp_score() {
        when(activeUsers.contains("20240001")).thenReturn(false);
        when(waitingTicketRepository.existsByRequesterUserIdAndStatusIn(eq("20240001"), any())).thenReturn(false);

        WaitingTicket saved = WaitingTicket.admission("20240001", "sess-1");
        // simulate generated id
        org.springframework.test.util.ReflectionTestUtils.setField(saved, "ticketId", 1042L);
        when(waitingTicketRepository.save(any(WaitingTicket.class))).thenReturn(saved);
        when(waitingQueue.rank("1042")).thenReturn(2);

        Object result = queueService.requestAdmission("20240001", "sess-1");

        TicketReceipt receipt = assertInstanceOf(TicketReceipt.class, result);
        assertEquals("1042", receipt.ticketId());
        assertEquals(TicketRequestType.ADMISSION, receipt.requestType());
        assertEquals("/api/v1/tickets/1042", receipt.statusUrl());

        ArgumentCaptor<Double> scoreCaptor = ArgumentCaptor.forClass(Double.class);
        verify(waitingQueue).add(scoreCaptor.capture(), eq("1042"));
        assertTrue(scoreCaptor.getValue() >= Instant.now().toEpochMilli() - 5_000);
    }

    @Test
    void admitWaitingUsers_pops_ticket_and_activates_user() {
        when(waitingQueue.pollFirst(50)).thenReturn(List.of("10"));
        WaitingTicket ticket = WaitingTicket.admission("20240001", "sess-1");
        org.springframework.test.util.ReflectionTestUtils.setField(ticket, "ticketId", 10L);
        when(waitingTicketRepository.findById(10L)).thenReturn(Optional.of(ticket));

        int admitted = queueService.admitWaitingUsers();

        assertEquals(1, admitted);
        verify(activeUsers).add("20240001", 5L, TimeUnit.MINUTES);
        assertEquals(TicketStatus.SUCCEEDED, ticket.getStatus());
    }

    @Test
    void admitWaitingUsers_returns_zero_when_empty() {
        when(waitingQueue.pollFirst(50)).thenReturn(List.of());
        assertEquals(0, queueService.admitWaitingUsers());
        verify(activeUsers, never()).add(anyString(), anyLong(), eq(TimeUnit.MINUTES));
    }
}
