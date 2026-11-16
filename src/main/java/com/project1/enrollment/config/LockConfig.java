package com.project1.enrollment.config;

import com.project1.enrollment.lock.DistributedLockProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(DistributedLockProperties.class)
public class LockConfig {
}
