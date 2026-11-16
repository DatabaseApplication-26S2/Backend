package com.project1.enrollment.config;

import com.project1.enrollment.queue.ActiveUserInterceptor;
import com.project1.enrollment.queue.QueueProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@EnableConfigurationProperties(QueueProperties.class)
public class QueueConfig implements WebMvcConfigurer {

    private final ActiveUserInterceptor activeUserInterceptor;

    public QueueConfig(ActiveUserInterceptor activeUserInterceptor) {
        this.activeUserInterceptor = activeUserInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(activeUserInterceptor)
                .addPathPatterns("/api/v1/me/enrollments", "/api/v1/me/enrollments/**");
    }
}
