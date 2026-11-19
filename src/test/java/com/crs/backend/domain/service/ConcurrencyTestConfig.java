package com.crs.backend.domain.service;

import org.mockito.Mockito;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@TestConfiguration
public class ConcurrencyTestConfig {

    @Bean
    @Primary
    public RedissonClient redissonClient() {
        RedissonClient client = Mockito.mock(RedissonClient.class);
        RScoredSortedSet<String> zset = Mockito.mock(RScoredSortedSet.class);
        when(client.<String>getScoredSortedSet(anyString())).thenReturn(zset);
        return client;
    }
}
