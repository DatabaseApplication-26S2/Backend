package com.crs.backend.queue;

import com.crs.backend.adapter.in.web.dto.AdmissionState;
import com.crs.backend.adapter.out.persistence.jpa.WaitingTicketJpaRepository;
import com.crs.backend.application.TicketDispatcher;
import com.crs.backend.application.port.TicketIntake;
import com.crs.backend.auth.AuthService;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class QueueServiceTest {

    private final AuthService auth = mock(AuthService.class);
    private final TicketIntake intake = mock(TicketIntake.class);
    private final TicketDispatcher dispatcher = mock(TicketDispatcher.class);
    private final QueueService service =
            new QueueService(auth, mock(WaitingTicketJpaRepository.class), intake, dispatcher);

    @Test
    void admissionUsesTheAuthenticatedSessionRatherThanRedisMembership() {
        var expected = new AdmissionState(Instant.parse("2026-11-18T00:00:00Z"));
        when(auth.requestAdmission("student", "session")).thenReturn(expected);
        assertSame(expected, service.requestAdmission("student", "session"));
        verifyNoInteractions(intake);
    }

    @Test
    void admitWaitingUsersDelegatesToTheDispatcher() {
        when(dispatcher.claimAndDispatch()).thenReturn(2);
        assertEquals(2, service.admitWaitingUsers());
        verify(dispatcher).claimAndDispatch();
    }
}
