package com.project1.enrollment.lock;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DistributedLockAspectTest {

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RLock rLock;

    @Mock
    private ProceedingJoinPoint joinPoint;

    @Mock
    private MethodSignature methodSignature;

    private DistributedLockAspect aspect;

    @BeforeEach
    void setUp() throws NoSuchMethodException {
        DistributedLockProperties properties = new DistributedLockProperties();
        properties.setWaitTime(5L);
        properties.setLeaseTime(10L);
        aspect = new DistributedLockAspect(redissonClient, properties);

        Method method = SampleService.class.getMethod("enroll", Long.class, Long.class);
        when(joinPoint.getSignature()).thenReturn(methodSignature);
        when(methodSignature.getMethod()).thenReturn(method);
        when(joinPoint.getTarget()).thenReturn(new SampleService());
        when(joinPoint.getArgs()).thenReturn(new Object[]{100L, 42L});
        when(redissonClient.getLock("lock:course:42")).thenReturn(rLock);
    }

    @Test
    void acquires_lock_then_unlocks_after_proceed() throws Throwable {
        when(rLock.tryLock(5L, 10L, TimeUnit.SECONDS)).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);
        when(joinPoint.proceed()).thenReturn("OK");

        Object result = aspect.lockAndExecute(joinPoint);

        assertEquals("OK", result);
        verify(rLock).tryLock(5L, 10L, TimeUnit.SECONDS);
        verify(joinPoint).proceed();
        verify(rLock).unlock();
    }

    @Test
    void unlocks_even_when_business_logic_fails() throws Throwable {
        when(rLock.tryLock(5L, 10L, TimeUnit.SECONDS)).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);
        when(joinPoint.proceed()).thenThrow(new IllegalStateException("boom"));

        assertThrows(IllegalStateException.class, () -> aspect.lockAndExecute(joinPoint));
        verify(rLock).unlock();
    }

    @Test
    void throws_when_lock_not_acquired() throws Throwable {
        when(rLock.tryLock(5L, 10L, TimeUnit.SECONDS)).thenReturn(false);

        assertThrows(LockAcquisitionException.class, () -> aspect.lockAndExecute(joinPoint));
        verify(joinPoint, never()).proceed();
        verify(rLock, never()).unlock();
    }

    @Test
    void unlock_happens_after_proceed_completes() throws Throwable {
        AtomicBoolean proceeded = new AtomicBoolean(false);
        AtomicBoolean unlockedAfterProceed = new AtomicBoolean(false);

        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);
        when(joinPoint.proceed()).thenAnswer(invocation -> {
            proceeded.set(true);
            return "OK";
        });
        org.mockito.Mockito.doAnswer(invocation -> {
            unlockedAfterProceed.set(proceeded.get());
            return null;
        }).when(rLock).unlock();

        aspect.lockAndExecute(joinPoint);

        assertTrue(unlockedAfterProceed.get());
        verify(rLock).tryLock(eq(5L), eq(10L), eq(TimeUnit.SECONDS));
    }

    static class SampleService {
        @DistributedLock(key = "#courseId")
        public String enroll(Long studentId, Long courseId) {
            return "OK";
        }
    }
}
