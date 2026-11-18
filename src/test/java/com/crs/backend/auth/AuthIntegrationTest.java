package com.crs.backend.auth;

import tools.jackson.databind.ObjectMapper;
import com.crs.backend.TestcontainersConfiguration;
import com.crs.backend.adapter.out.persistence.jpa.*;
import com.crs.backend.domain.entity.*;
import com.crs.backend.domain.enums.*;
import com.crs.backend.domain.service.ConcurrencyTestConfig;
import com.crs.backend.queue.QueueService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "queue.scheduler-fixed-delay-ms=3600000",
    "spring.autoconfigure.exclude=org.redisson.spring.starter.RedissonAutoConfigurationV2,org.redisson.spring.starter.RedissonAutoConfigurationV4"
})
@Import({ConcurrencyTestConfig.class, TestcontainersConfiguration.class})
@AutoConfigureMockMvc
class AuthIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AuthService auth;
    @Autowired UserAccountRepository users;
    @Autowired WaitingTicketJpaRepository tickets;
    @Autowired LoginCredentialStore credentials;
    @Autowired QueueService queue;

    @BeforeEach void seed() {
        users.save(UserAccount.builder().userId("20240001").userName("Demo")
            .passwordHash(new BCryptPasswordEncoder(10).encode("Demo-password1!")).role(UserRole.STUDENT).build());
    }
    private AuthService.LoginReceipt login(String password) {
        return auth.login("20240001", password.toCharArray());
    }
    private void process(AuthService.LoginReceipt receipt) {
        // DB claim 디스패처가 WAITING LOGIN 티켓을 잡아 processLogin 한다 (Redis poll 의존 제거)
        assertNotNull(receipt.ticketId());
        queue.admitWaitingUsers();
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
