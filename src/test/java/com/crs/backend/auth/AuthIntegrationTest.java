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
import java.util.List;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@org.springframework.test.context.ActiveProfiles("test")
@SpringBootTest(properties = {
    "queue.scheduler-fixed-delay-ms=3600000",
    "spring.autoconfigure.exclude=org.redisson.spring.starter.RedissonAutoConfigurationV2,org.redisson.spring.starter.RedissonAutoConfigurationV4"
})
@Import({ConcurrencyTestConfig.class, TestcontainersConfiguration.class})
@AutoConfigureMockMvc
@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
class AuthIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AuthService auth;
    @Autowired UserAccountRepository users;
    @Autowired WaitingTicketJpaRepository tickets;
    @Autowired LoginCredentialStore credentials;
    @Autowired QueueService queue;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired org.springframework.transaction.support.TransactionTemplate tx;
    @jakarta.persistence.PersistenceContext jakarta.persistence.EntityManager em;

    @BeforeEach void seed() {
        jdbc.execute("TRUNCATE enrollments, refresh_tokens, sessions, tickets");
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
        var result = mvc.perform(post("/api/v1/auth/login").header("Origin", "http://localhost:5173").header("X-CSRF-Protection", "1").contentType("application/json")
            .content("{\"userId\":\"20240001\",\"password\":\"Demo-password1!\"}"))
            .andExpect(status().isAccepted()).andReturn();
        String id = json.readTree(result.getResponse().getContentAsString()).get("ticketId").asText();
        ContractSchemaTest.assertValid("LoginTicketReceipt", result.getResponse().getContentAsString());
        String proof = result.getResponse().getCookie("__Host-crs_login").getValue();
        var receipt = new AuthService.LoginReceipt(id, TicketRequestType.LOGIN, TicketStatus.WAITING, null, null, proof);
        process(receipt);
        mvc.perform(get("/api/v1/tickets/" + id).cookie(new Cookie("__Host-crs_login", proof)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.result.code").value("OK"));
        var exchanged = mvc.perform(post("/api/v1/auth/login-tickets/" + id + "/tokens").header("Origin", "http://localhost:5173").header("X-CSRF-Protection", "1")
            .cookie(new Cookie("__Host-crs_login", proof))).andExpect(status().isOk()).andReturn();
        String access = json.readTree(exchanged.getResponse().getContentAsString()).get("accessToken").asText();
        ContractSchemaTest.assertValid("TokenResponse", exchanged.getResponse().getContentAsString());
        String refresh = exchanged.getResponse().getCookie("__Host-crs_refresh").getValue();
        mvc.perform(post("/api/v1/admission").header("Authorization", "Bearer " + access))
            .andExpect(status().isAccepted());
        mvc.perform(post("/api/v1/auth/logout").header("Origin", "http://localhost:5173").header("X-CSRF-Protection", "1").cookie(new Cookie("__Host-crs_refresh", refresh)))
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

    private AuthService.IssuedTokens issued() {
        var receipt = login("Demo-password1!"); process(receipt);
        return auth.exchange(Long.parseLong(receipt.ticketId()), receipt.proof());
    }
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder cookiePost(String path) {
        return post(path).header("Origin", "http://localhost:5173").header("X-CSRF-Protection", "1");
    }
    @Test void duplicateLoginAndCredentialDeadlineUseDatabaseState() throws Exception {
        var receipt = login("Demo-password1!");
        var duplicate = assertThrows(AuthFailure.class, () -> auth.login("20240001", "x".toCharArray(), receipt.proof()));
        assertEquals(receipt.ticketId(), duplicate.body().get("activeTicketId"));
        ContractSchemaTest.assertValid("ProblemDetails", json.writeValueAsString(duplicate.body()));
        jdbc.update("UPDATE tickets SET credential_expires_at=clock_timestamp()-interval '1 second' WHERE ticket_id=?", Long.parseLong(receipt.ticketId()));
        process(receipt);
        assertEquals(TicketResultCode.E01, tickets.findById(Long.parseLong(receipt.ticketId())).orElseThrow().getResultCode());
    }
    @Test void simultaneousRefreshHasOneWinnerAndReuseRevokesTheSession() throws Exception {
        var issued = issued();
        var executor = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        Callable<Object> rotate = () -> {
            start.await();
            try { return auth.refresh(issued.refreshToken()); } catch (AuthFailure ex) { return ex; }
        };
        try {
            var a = executor.submit(rotate); var b = executor.submit(rotate); start.countDown();
            var first = a.get(15, TimeUnit.SECONDS); var second = b.get(15, TimeUnit.SECONDS);
            assertTrue((first instanceof AuthService.IssuedTokens) != (second instanceof AuthService.IssuedTokens));
            AuthFailure failed = (AuthFailure) (first instanceof AuthFailure ? first : second);
            assertEquals("REFRESH_TOKEN_REUSED", failed.body().get("reason"));
            assertEquals("SESSION_REVOKED", assertThrows(AuthFailure.class, () -> auth.authenticate(issued.accessToken())).body().get("reason"));
            mvc.perform(cookiePost("/api/v1/auth/refresh").cookie(new Cookie("__Host-crs_refresh", issued.refreshToken())))
                .andExpect(status().isUnauthorized()).andExpect(cookie().maxAge("__Host-crs_refresh", 0));
        } finally { executor.shutdownNow(); }
    }
    @Test void admissionIsPerSessionAndSurvivesRedisDeletionAndRefresh() throws Exception {
        var first = issued(); var firstUser = auth.authenticate(first.accessToken());
        auth.requestAdmission(firstUser.userId(), firstUser.sessionId()); queue.admitWaitingUsers();
        assertTrue(auth.isAdmitted(firstUser.userId(), firstUser.sessionId()));
        var rotated = auth.refresh(first.refreshToken());
        assertNotNull(rotated.session().admittedAt());
        assertEquals(rotated.session().admittedAt(),
            ((com.crs.backend.adapter.in.web.dto.AdmissionState) auth.requestAdmission(firstUser.userId(), firstUser.sessionId())).admittedAt());
        var second = issued(); var secondUser = auth.authenticate(second.accessToken());
        assertFalse(auth.isAdmitted(secondUser.userId(), secondUser.sessionId()));
        mvc.perform(get("/api/v1/courses").header("Authorization", "Bearer " + second.accessToken()))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("E15"));
        var receipt = (com.crs.backend.adapter.in.web.dto.TicketReceipt) auth.requestAdmission(secondUser.userId(), secondUser.sessionId());
        auth.logout(second.refreshToken()); queue.admitWaitingUsers();
        assertEquals(TicketResultCode.E02, tickets.findById(Long.parseLong(receipt.ticketId())).orElseThrow().getResultCode());
    }
    @Test void logoutCookieHasPriorityAndAccessFallbackIsIdempotent() throws Exception {
        var issued = issued();
        auth.logout("unknown", "Bearer " + issued.accessToken());
        assertNotNull(auth.authenticate(issued.accessToken()));
        mvc.perform(cookiePost("/api/v1/auth/logout").header("Authorization", "Bearer " + issued.accessToken()))
            .andExpect(status().isNoContent()).andExpect(cookie().maxAge("__Host-crs_login", 0))
            .andExpect(cookie().maxAge("__Host-crs_refresh", 0));
        assertThrows(AuthFailure.class, () -> auth.authenticate(issued.accessToken()));
        mvc.perform(cookiePost("/api/v1/auth/logout").header("Authorization", "Bearer " + issued.accessToken()))
            .andExpect(status().isNoContent());
    }
    @Test void strictRequestsAndCorsFollowTheContract() throws Exception {
        mvc.perform(post("/api/v1/auth/login").header("Origin", "https://wrong.example").header("X-CSRF-Protection", "1")
            .contentType("application/json").content("{}"))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.reason").value("CSRF_CHECK_FAILED"));
        for (String body : List.of("{\"userId\":4,\"password\":\"x\"}", "{\"userId\":\"s\",\"password\":[\"x\"]}",
                "{\"userId\":\"s\",\"password\":\"x\",\"extra\":true}", "{\"userId\":\"s\",\"password\":\"\"}")) {
            var result = mvc.perform(cookiePost("/api/v1/auth/login").contentType("application/json").content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("E09")).andReturn();
            ContractSchemaTest.assertValid("ProblemDetails", result.getResponse().getContentAsString());
        }
        for (String path : List.of("/api/v1/auth/refresh", "/api/v1/auth/logout", "/api/v1/auth/login-tickets/1/tokens")) {
            mvc.perform(cookiePost(path).contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.violations[0].source").value("BODY"));
        }
        mvc.perform(cookiePost("/api/v1/auth/login-tickets/+1/tokens"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.violations[0].source").value("PATH"));
        mvc.perform(get("/api/v1/tickets/999999999999999999999999999999"))
            .andExpect(status().isBadRequest());
        mvc.perform(options("/api/v1/auth/login").header("Origin", "http://localhost:5173")
            .header("Access-Control-Request-Method", "POST").header("Access-Control-Request-Headers", "content-type,x-csrf-protection"))
            .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Credentials", "true"));
    }
    @Test void staleLoginWorkerCannotConsumeTheNewGenerationCredentials() {
        var receipt = login("Demo-password1!");
        long id = Long.parseLong(receipt.ticketId());
        tx.executeWithoutResult(status -> {
            var stale = em.find(WaitingTicket.class, id);
            stale.markProcessing(jdbc.queryForObject("SELECT clock_timestamp()", java.sql.Timestamp.class).toInstant()); em.flush();
            jdbc.update("UPDATE tickets SET generation_no=generation_no+1,slot_claim_ref=generation_no+1,owner_worker_ref='new-worker' WHERE ticket_id=?", id);
            auth.processLogin(stale);
            assertEquals("PROCESSING", jdbc.queryForObject("SELECT status_cd FROM tickets WHERE ticket_id=?", String.class, id));
        });
        tx.executeWithoutResult(status -> auth.processLogin(em.find(WaitingTicket.class, id)));
        assertEquals(TicketResultCode.OK, tickets.findById(id).orElseThrow().getResultCode());
    }
    @Test void plaintextSecretsAreAbsentFromDatabaseAndValueObjects(org.springframework.boot.test.system.CapturedOutput output) {
        var receipt = login("Demo-password1!"); process(receipt);
        var issued = auth.exchange(Long.parseLong(receipt.ticketId()), receipt.proof());
        for (String table : List.of("users", "tickets", "sessions", "refresh_tokens")) {
            String dump = String.join("\n", jdbc.queryForList("SELECT row_to_json(t)::text FROM " + table + " t", String.class));
            for (String secret : List.of("Demo-password1!", receipt.proof(), issued.accessToken(), issued.refreshToken()))
                assertFalse(dump.contains(secret), "Plaintext secret persisted in " + table);
        }
        assertEquals("IssuedTokens[redacted]", issued.toString());
        assertEquals("LoginReceipt[redacted]", receipt.toString());
        for (String secret : List.of("Demo-password1!", receipt.proof(), issued.accessToken(), issued.refreshToken()))
            assertFalse(output.getAll().contains(secret), "Plaintext secret leaked to logs");
    }

}
