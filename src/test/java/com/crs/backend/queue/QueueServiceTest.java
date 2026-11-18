package com.crs.backend.queue;

import com.crs.backend.adapter.in.web.dto.AdmissionState;
import com.crs.backend.adapter.in.web.dto.TicketReceipt;
import com.crs.backend.adapter.in.web.dto.TicketReceiptStatus;
import com.crs.backend.adapter.out.persistence.jpa.AccessSessionRepository;
import com.crs.backend.adapter.out.persistence.jpa.WaitingTicketJpaRepository;
import com.crs.backend.application.TicketDispatcher;
import com.crs.backend.application.port.TicketIntake;
import com.crs.backend.domain.entity.AccessSession;
import com.crs.backend.domain.enums.SessionStatus;
import com.crs.backend.domain.enums.TicketRequestType;
import com.crs.backend.domain.enums.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RSetCache;
import org.redisson.api.RedissonClient;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QueueServiceTest {

    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RSetCache<String> activeUsers;
    @Mock
    private WaitingTicketJpaRepository waitingTicketRepository;
    @Mock
    private AccessSessionRepository accessSessionRepository;
    @Mock
    private TicketIntake ticketIntake;
    @Mock
    private TicketDispatcher ticketDispatcher;

    private QueueService queueService;

    @BeforeEach
    void setUp() {
        QueueProperties properties = new QueueProperties();
        properties.setWaitingKey("waiting_queue");
        properties.setActiveKey("active_users");
        properties.setAdmitBatchSize(50);
        properties.setActiveTtlMinutes(5L);

        queueService = new QueueService(
                redissonClient,
                properties,
                waitingTicketRepository,
                accessSessionRepository,
                ticketIntake,
                ticketDispatcher
        );

        org.mockito.Mockito.lenient()
                .when(redissonClient.<String>getSetCache("active_users"))
                .thenReturn(activeUsers);
    }

    @Test
    void requestAdmission_returns_state_when_already_active() {
        Instant enteredAt = Instant.parse("2026-11-18T01:00:00Z");
        when(activeUsers.contains("20240001")).thenReturn(true);
        when(accessSessionRepository.findById("sess-1")).thenReturn(java.util.Optional.of(
                AccessSession.builder()
                        .sessionId("sess-1")
                        .userId("20240001")
                        .role(UserRole.STUDENT)
                        .loginTicketId(1L)
                        .loggedInAt(enteredAt.minusSeconds(60))
                        .expiresAt(enteredAt.plusSeconds(3600))
                        .sessionStatus(SessionStatus.ACTIVE)
                        .enteredAt(enteredAt)
                        .build()
        ));

        Object result = queueService.requestAdmission("20240001", "sess-1");
        AdmissionState state = assertInstanceOf(AdmissionState.class, result);
        assertEquals(enteredAt, state.admittedAt());
    }

    @Test
    void requestAdmission_delegates_to_ticket_intake() {
        when(activeUsers.contains("20240001")).thenReturn(false);
        TicketReceipt receipt = new TicketReceipt(
                "10", TicketRequestType.ADMISSION, TicketReceiptStatus.WAITING,
                Instant.now(), "/api/v1/tickets/10"
        );
        when(ticketIntake.intakeAdmission("20240001", "sess-1")).thenReturn(receipt);

        Object result = queueService.requestAdmission("20240001", "sess-1");
        assertEquals(receipt, result);
        verify(ticketIntake).intakeAdmission("20240001", "sess-1");
    }

    @Test
    void admitWaitingUsers_delegates_to_dispatcher() {
        when(ticketDispatcher.claimAndDispatch()).thenReturn(2);
        assertEquals(2, queueService.admitWaitingUsers());
        verify(ticketDispatcher).claimAndDispatch();
    }
}
