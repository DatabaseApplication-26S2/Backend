package com.crs.backend.lock;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.TimeUnit;

/**
 * Redisson 분산 락을 적용한다.
 * <p>
 * AOP가 락 획득 → REQUIRES_NEW 트랜잭션 실행 → 커밋 후 락 해제를 보장한다.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DistributedLock {

    /**
     * SpEL 표현식. 예: {@code #courseId}
     * 최종 락 키는 {@code keyPrefix + evaluatedKey} 형태가 된다.
     */
    String key();

    /**
     * 락 키 prefix. 수강신청 기본값: {@code lock:course:}
     */
    String keyPrefix() default "lock:course:";

    long waitTime() default -1L;

    long leaseTime() default -1L;

    TimeUnit timeUnit() default TimeUnit.SECONDS;
}
