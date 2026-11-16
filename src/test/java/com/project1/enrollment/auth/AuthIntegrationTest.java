package com.project1.enrollment.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project1.enrollment.adapter.out.persistence.jpa.*;
import com.project1.enrollment.domain.entity.*;
import com.project1.enrollment.domain.enums.*;
import com.project1.enrollment.domain.service.ConcurrencyTestConfig;
import com.project1.enrollment.queue.QueueService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.redisson.api.*;
import java.util.*;
import java.util.concurrent.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:auth-test;DB_CLOSE_DELAY=-1",
    "queue.scheduler-fixed-delay-ms=3600000",
    "spring.autoconfigure.exclude=org.redisson.spring.starter.RedissonAutoConfigurationV2"
})
@Import(ConcurrencyTestConfig.class)
@AutoConfigureMockMvc
class AuthIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AuthService auth;
    @Autowired UserAccountRepository users;
    @Autowired WaitingTicketJpaRepository tickets;
    @Autowired LoginCredentialStore credentials;
    @Autowired QueueService queue;
    @Autowired RedissonClient redis;

    @BeforeEach void seed() {
        users.save(UserAccount.builder().userId("20240001").userName("Demo")
            .passwordHash(new BCryptPasswordEncoder(10).encode("Demo-password1!")).role(UserRole.STUDENT).build());
    }
    private AuthService.LoginReceipt login(String password) {
        return auth.login("20240001", password.toCharArray());
    }
    private void process(AuthService.LoginReceipt receipt) {
        RScoredSortedSet<String> set = redis.getScoredSortedSet("waiting_queue");
        when(set.pollFirst(anyInt())).thenReturn(List.of(receipt.ticketId()));
        queue.admitWaitingUsers();
        when(set.pollFirst(anyInt())).thenReturn(List.of());
    }
    @Test void httpLoginTokensProtectedApiAndLogout() throws Exception {
        var result = mvc.perform(post("/api/v1/auth/login").contentType("application/json")
            .content("{\"userId\":\"20240001\",\"password\":\"Demo-password1!\"}"))
            .andExpect(status().isAccepted()).andReturn();
        String id = json.readTree(result.getResponse().getContentAsString()).get("ticketId").asText();
        String proof = result.getResponse().getCookie("__Host-crs_login").getValue();
        var receipt = new AuthService.LoginReceipt(id, TicketRequestType.LOGIN, TicketStatus.WAITING, null, null, proof);
        process(receipt);
        mvc.perform(get("/api/v1/tickets/" + id).cookie(new Cookie("__Host-crs_login", proof)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.result.code").value("OK"));
        var exchanged = mvc.perform(post("/api/v1/auth/login-tickets/" + id + "/tokens")
            .cookie(new Cookie("__Host-crs_login", proof))).andExpect(status().isOk()).andReturn();
        String access = json.readTree(exchanged.getResponse().getContentAsString()).get("accessToken").asText();
        String refresh = exchanged.getResponse().getCookie("__Host-crs_refresh").getValue();
        mvc.perform(post("/api/v1/admission").header("Authorization", "Bearer " + access))
            .andExpect(status().isAccepted());
        mvc.perform(post("/api/v1/auth/logout").cookie(new Cookie("__Host-crs_refresh", refresh)))
            .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/admission").header("Authorization", "Bearer " + access))
            .andExpect(status().isUnauthorized());
    }
    @Test void wrongPasswordAndLostCredentialsReject() {
        var wrong = login("wrong");
        process(wrong);
        assertEquals(TicketResultCode.E01, tickets.findById(Long.parseLong(wrong.ticketId())).orElseThrow().getResultCode());
        var lost = login("Demo-password1!");
        credentials.discard(tickets.findById(Long.parseLong(lost.ticketId())).orElseThrow().getAnonContextId());
        process(lost);
        assertEquals(TicketResultCode.E01, tickets.findById(Long.parseLong(lost.ticketId())).orElseThrow().getResultCode());
    }
    @Test void concurrentExchangeOnlyOneSucceedsAndRefreshReuseRevokes() throws Exception {
        var receipt = login("Demo-password1!");
        process(receipt);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<AuthService.IssuedTokens> exchange = () -> {
            start.await();
            try { return auth.exchange(Long.parseLong(receipt.ticketId()), receipt.proof()); }
            catch (AuthFailure failure) { assertEquals(403, failure.status()); return null; }
        };
        try {
            var a = executor.submit(exchange);
            var b = executor.submit(exchange);
            start.countDown();
            var first = a.get(10, TimeUnit.SECONDS);
            var second = b.get(10, TimeUnit.SECONDS);
            assertTrue((first == null) != (second == null));
            var issued = first == null ? second : first;
            var rotated = auth.refresh(issued.refreshToken());
            assertNotNull(auth.authenticate(rotated.accessToken()));
            assertThrows(AuthFailure.class, () -> auth.refresh(issued.refreshToken()));
            assertThrows(AuthFailure.class, () -> auth.authenticate(rotated.accessToken()));
        } finally { executor.shutdownNow(); }
    }
    @Test void forgedHeadersAndWrongProofAreRejected() throws Exception {
        mvc.perform(post("/api/v1/admission").header("X-User-Id", "20240001").header("X-Session-Id", "fake"))
            .andExpect(status().isUnauthorized());
        var receipt = login("Demo-password1!");
        mvc.perform(get("/api/v1/tickets/" + receipt.ticketId()).cookie(new Cookie("__Host-crs_login", "wrong")))
            .andExpect(status().isForbidden());
    }
}
