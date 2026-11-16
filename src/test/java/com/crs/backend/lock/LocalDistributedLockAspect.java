package com.crs.backend.lock;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.annotation.Order;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 통합 테스트용 로컬 락. 운영 {@link DistributedLockAspect}와 동일하게
 * 락만 잡고, 트랜잭션은 메서드의 {@code @Transactional(REQUIRES_NEW)}에 맡긴다.
 */
@Aspect
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LocalDistributedLockAspect {

    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final DistributedLockProperties properties;

    public LocalDistributedLockAspect(DistributedLockProperties properties) {
        this.properties = properties;
    }

    @Around("@annotation(com.crs.backend.lock.DistributedLock)")
    public Object lockAndExecute(ProceedingJoinPoint joinPoint) throws Throwable {
        DistributedLock distributedLock = resolveAnnotation(joinPoint);
        String lockKey = DistributedLockKeyParser.parse(
                joinPoint,
                distributedLock.keyPrefix(),
                distributedLock.key()
        );

        long waitTime = distributedLock.waitTime() >= 0 ? distributedLock.waitTime() : properties.getWaitTime();
        TimeUnit timeUnit = distributedLock.timeUnit();

        ReentrantLock lock = locks.computeIfAbsent(lockKey, key -> new ReentrantLock(true));
        boolean acquired;
        try {
            acquired = lock.tryLock(waitTime, timeUnit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LockAcquisitionException(lockKey);
        }

        if (!acquired) {
            throw new LockAcquisitionException(lockKey);
        }

        try {
            return joinPoint.proceed();
        } finally {
            lock.unlock();
        }
    }

    private DistributedLock resolveAnnotation(ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        DistributedLock annotation = AnnotationUtils.findAnnotation(method, DistributedLock.class);
        if (annotation == null) {
            Method specific = AopUtils.getMostSpecificMethod(method, joinPoint.getTarget().getClass());
            annotation = AnnotationUtils.findAnnotation(specific, DistributedLock.class);
        }
        if (annotation == null) {
            throw new IllegalStateException("@DistributedLock not found on " + method);
        }
        return annotation;
    }
}
