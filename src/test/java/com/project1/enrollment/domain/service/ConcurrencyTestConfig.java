package com.project1.enrollment.domain.service;

import com.project1.enrollment.lock.DistributedLockProperties;
import com.project1.enrollment.lock.LocalDistributedLockAspect;
import org.mockito.Mockito;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RSetCache;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Primary;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@TestConfiguration
@EnableAspectJAutoProxy(proxyTargetClass = true)
public class ConcurrencyTestConfig {

    @Bean
    @Primary
    public RedissonClient redissonClient() {
        RedissonClient client = Mockito.mock(RedissonClient.class);
        RScoredSortedSet<String> zset = Mockito.mock(RScoredSortedSet.class);
        RSetCache<String> setCache = Mockito.mock(RSetCache.class);
        when(client.<String>getScoredSortedSet(anyString())).thenReturn(zset);
        when(client.<String>getSetCache(anyString())).thenReturn(setCache);
        return client;
    }

    @Bean
    public LocalDistributedLockAspect localDistributedLockAspect(DistributedLockProperties properties) {
        return new LocalDistributedLockAspect(properties);
    }
}
