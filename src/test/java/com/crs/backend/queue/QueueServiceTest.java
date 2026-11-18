package com.crs.backend.queue;
import com.crs.backend.auth.AuthService;
import com.crs.backend.adapter.in.web.dto.AdmissionState;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class QueueServiceTest {
    @Test void admissionUsesTheAuthenticatedSessionRatherThanRedisMembership() {
        var auth = mock(AuthService.class);
        var redis = mock(org.redisson.api.RedissonClient.class);
        var service = new QueueService(redis, new QueueProperties(), null, null);
        ReflectionTestUtils.setField(service, "authService", auth);
        var expected = new AdmissionState(Instant.parse("2026-11-18T00:00:00Z"));
        when(auth.requestAdmission("student", "session")).thenReturn(expected);
        assertSame(expected, service.requestAdmission("student", "session"));
        verifyNoInteractions(redis);
    }
}