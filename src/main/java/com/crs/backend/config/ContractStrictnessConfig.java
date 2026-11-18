package com.crs.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * BE-15 계약 엄격성 검사를 /api/v1/** 에 건다.
 * ActiveUserInterceptor(order 0, /me/enrollments 입장 검사 E15)보다 뒤에 실행되도록 order를 1로 둔다.
 */
@Configuration
public class ContractStrictnessConfig implements WebMvcConfigurer {

    private final ContractStrictnessInterceptor interceptor;

    public ContractStrictnessConfig(ContractStrictnessInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).addPathPatterns("/api/v1/**").order(1);
    }
}
