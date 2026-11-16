package com.crs.backend.config;

import com.crs.backend.lock.DistributedLockProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(DistributedLockProperties.class)
public class LockConfig {
}
