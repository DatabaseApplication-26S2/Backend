package com.crs.backend.queue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActiveUserInterceptorTest {

    @Mock
    private com.crs.backend.auth.AuthService queueService;

    private ActiveUserInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new ActiveUserInterceptor(queueService);
    }

    @Test
    void allows_request_when_user_is_active() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(QueueController.USER_ID_HEADER, "user-1"); request.addHeader(QueueController.SESSION_ID_HEADER, "session-1");
        when(queueService.isAdmitted("user-1", "session-1")).thenReturn(true);

        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
    }

    @Test
    void rejects_request_when_user_is_not_active() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(QueueController.USER_ID_HEADER, "user-1"); request.addHeader(QueueController.SESSION_ID_HEADER, "session-1");
        when(queueService.isAdmitted("user-1", "session-1")).thenReturn(false);

        assertThrows(
                QueueNotAllowedException.class,
                () -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object())
        );
    }

    @Test
    void rejects_request_when_user_id_missing() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertThrows(
                QueueNotAllowedException.class,
                () -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object())
        );
    }
}
