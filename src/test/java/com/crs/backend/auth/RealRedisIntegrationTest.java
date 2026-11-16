package com.crs.backend.auth;

import tools.jackson.databind.ObjectMapper;
import com.crs.backend.TestcontainersConfiguration;
import com.crs.backend.adapter.out.persistence.jpa.UserAccountRepository;
import com.crs.backend.domain.entity.UserAccount;
import com.crs.backend.domain.enums.UserRole;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.redisson.api.RedissonClient;
import org.redisson.api.redisnode.RedisNodes;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.UUID;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Tag("redis")
@SpringBootTest(properties = {
    "spring.data.redis.host=127.0.0.1",
    "queue.scheduler-fixed-delay-ms=100"
})
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class RealRedisIntegrationTest {
    private static final String PREFIX = "auth-test:" + UUID.randomUUID();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.port", () -> System.getenv().getOrDefault("REDIS_TEST_PORT", "16379"));
        registry.add("queue.waiting-key", () -> PREFIX + ":waiting");
        registry.add("queue.active-key", () -> PREFIX + ":active");
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserAccountRepository users;
    @Autowired RedissonClient redis;
    @Autowired Flyway flyway;

    @BeforeEach void seed() {
        users.save(UserAccount.builder().userId("redis-student").userName("Redis integration")
            .passwordHash(new BCryptPasswordEncoder(10).encode("Demo-password1!")).role(UserRole.STUDENT).build());
    }
    @AfterEach void cleanup() {
        redis.getKeys().deleteByPattern(PREFIX + "*");
    }
    private String[] login(String password) throws Exception {
        var response = mvc.perform(post("/api/v1/auth/login").contentType("application/json")
            .content(json.writeValueAsString(java.util.Map.of("userId", "redis-student", "password", password))))
            .andExpect(status().isAccepted()).andReturn().getResponse();
        return new String[] {json.readTree(response.getContentAsString()).get("ticketId").asText(),
            response.getCookie("__Host-crs_login").getValue()};
    }
    @Test void schedulerProcessesLoginAdmissionRefreshAndLogoutWithRealRedis() throws Exception {
        assertEquals("1", flyway.info().current().getVersion().getVersion());
        assertTrue(redis.getRedisNodes(RedisNodes.SINGLE).pingAll());
        String[] login = login("Demo-password1!");
        Cookie proof = new Cookie("__Host-crs_login", login[1]);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
            mvc.perform(get("/api/v1/tickets/" + login[0]).cookie(proof))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.code").value("OK")));
        var exchange = mvc.perform(post("/api/v1/auth/login-tickets/" + login[0] + "/tokens").cookie(proof))
            .andExpect(status().isOk()).andReturn().getResponse();
        String access = json.readTree(exchange.getContentAsString()).get("accessToken").asText();
        Cookie refresh = new Cookie("__Host-crs_refresh", exchange.getCookie("__Host-crs_refresh").getValue());
        var admission = mvc.perform(post("/api/v1/admission").header("Authorization", "Bearer " + access))
            .andExpect(status().isAccepted()).andReturn().getResponse();
        String admissionId = json.readTree(admission.getContentAsString()).get("ticketId").asText();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
            mvc.perform(get("/api/v1/tickets/" + admissionId).header("Authorization", "Bearer " + access))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.code").value("OK")));
        assertTrue(redis.getSetCache(PREFIX + ":active").contains("redis-student"));
        var rotated = mvc.perform(post("/api/v1/auth/refresh").cookie(refresh))
            .andExpect(status().isOk()).andReturn().getResponse();
        Cookie next = new Cookie("__Host-crs_refresh", rotated.getCookie("__Host-crs_refresh").getValue());
        mvc.perform(post("/api/v1/auth/logout").cookie(next)).andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/admission").header("Authorization", "Bearer " + access))
            .andExpect(status().isUnauthorized());
    }
    @Test void schedulerRejectsWrongPassword() throws Exception {
        String[] login = login("wrong-password");
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
            mvc.perform(get("/api/v1/tickets/" + login[0]).cookie(new Cookie("__Host-crs_login", login[1])))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.code").value("E01")));
    }
}
