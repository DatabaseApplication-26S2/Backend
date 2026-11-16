package com.project1.enrollment.lock;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

/**
 * Redisson 분산 락 AOP.
 * <p>
 * 락 경계(필수):
 * <pre>
 *   락 획득 → (@Transactional REQUIRES_NEW) 비즈니스 → 커밋 → 락 해제
 * </pre>
 * 트랜잭션은 메서드의 {@code @Transactional(REQUIRES_NEW)}가 담당한다.
 * 이 Aspect는 {@link Ordered#HIGHEST_PRECEDENCE}로 트랜잭션 어드바이스보다 바깥에서
 * 동작하므로, {@code proceed()} 반환 = 커밋 완료 후 unlock 이 보장된다.
 */
@Aspect
@Component
@Profile("!concurrency-test")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DistributedLockAspect {

    private final RedissonClient redissonClient;
    private final DistributedLockProperties properties;

    public DistributedLockAspect(RedissonClient redissonClient, DistributedLockProperties properties) {
        this.redissonClient = redissonClient;
        this.properties = properties;
    }

    @Around("@annotation(com.project1.enrollment.lock.DistributedLock)")
    public Object lockAndExecute(ProceedingJoinPoint joinPoint) throws Throwable {
        DistributedLock distributedLock = resolveAnnotation(joinPoint);

        String lockKey = DistributedLockKeyParser.parse(
                joinPoint,
                distributedLock.keyPrefix(),
                distributedLock.key()
        );

        long waitTime = distributedLock.waitTime() >= 0 ? distributedLock.waitTime() : properties.getWaitTime();
        long leaseTime = distributedLock.leaseTime() >= 0 ? distributedLock.leaseTime() : properties.getLeaseTime();
        TimeUnit timeUnit = distributedLock.timeUnit();

        RLock lock = redissonClient.getLock(lockKey);
        boolean acquired;
        try {
            acquired = lock.tryLock(waitTime, leaseTime, timeUnit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LockAcquisitionException(lockKey);
        }

        if (!acquired) {
            throw new LockAcquisitionException(lockKey);
        }

        try {
            // proceed() 내부에서 @Transactional(REQUIRES_NEW)가 커밋까지 끝낸 뒤 반환된다.
            return joinPoint.proceed();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
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
