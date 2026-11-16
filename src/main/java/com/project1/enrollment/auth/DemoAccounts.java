package com.project1.enrollment.auth;

import com.project1.enrollment.adapter.out.persistence.jpa.UserAccountRepository;
import com.project1.enrollment.domain.entity.UserAccount;
import com.project1.enrollment.domain.enums.UserRole;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@Configuration
@Profile("demo")
public class DemoAccounts {
    @Bean ApplicationRunner demoUsers(UserAccountRepository users) {
        return args -> {
            if (!users.existsById("20240001")) {
                users.save(UserAccount.builder().userId("20240001").userName("시연 학생")
                    .passwordHash(new BCryptPasswordEncoder(10).encode("Demo-password1!"))
                    .role(UserRole.STUDENT).build());
            }
        };
    }
}
