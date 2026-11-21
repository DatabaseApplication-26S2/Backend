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
    @Autowired com.crs.backend.application.TicketDispatcher dispatcher;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired org.springframework.transaction.support.TransactionTemplate tx;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean TokenCodec codec;
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
        assertTrue(dispatcher.awaitIdle(java.time.Duration.ofSeconds(10)));
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
        Callable<org.springframework.mock.web.MockHttpServletResponse> rotate = () -> {
            start.await();
            return mvc.perform(cookiePost("/api/v1/auth/refresh")
                .cookie(new Cookie("__Host-crs_refresh", issued.refreshToken()))).andReturn().getResponse();
        };
        try {
            var a = executor.submit(rotate); var b = executor.submit(rotate); start.countDown();
            var first = a.get(15, TimeUnit.SECONDS); var second = b.get(15, TimeUnit.SECONDS);
            assertEquals(List.of(200, 401), java.util.stream.Stream.of(first, second)
                .map(org.springframework.mock.web.MockHttpServletResponse::getStatus).sorted().toList());
            OpenApiResponses.verify("/auth/refresh", "post", first);
            OpenApiResponses.verify("/auth/refresh", "post", second);
            var failed = first.getStatus() == 401 ? first : second;
            assertEquals("REFRESH_TOKEN_REUSED", json.readTree(failed.getContentAsString()).get("reason").asText());
            assertEquals("SESSION_REVOKED", assertThrows(AuthFailure.class, () -> auth.authenticate(issued.accessToken())).body().get("reason"));
            mvc.perform(cookiePost("/api/v1/auth/refresh").cookie(new Cookie("__Host-crs_refresh", issued.refreshToken())))
                .andExpect(status().isUnauthorized()).andExpect(cookie().maxAge("__Host-crs_refresh", 0));
        } finally { executor.shutdownNow(); }
    }
    @Test void admissionIsPerSessionAndSurvivesRedisDeletionAndRefresh() throws Exception {
        var first = issued(); var firstUser = auth.authenticate(first.accessToken());
        auth.requestAdmission(firstUser.userId(), firstUser.sessionId()); queue.admitWaitingUsers();
        assertTrue(dispatcher.awaitIdle(java.time.Duration.ofSeconds(10)));
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
        assertTrue(dispatcher.awaitIdle(java.time.Duration.ofSeconds(10)));
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


    @Test void expirationDuringIssuanceRollsBackBothRefreshWrites() {
        var token = issued();
        String sid = auth.authenticate(token.accessToken()).sessionId();
        int before = jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE session_id=CAST(? AS uuid)", Integer.class, sid);
        org.mockito.Mockito.doAnswer(invocation -> {
            // Deterministically move the token's issuance time to the final fractional second.
            var expiry = invocation.getArgument(3, java.time.Instant.class);
            return new TokenCodec(AuthService.randomToken()).issue(invocation.getArgument(0), invocation.getArgument(1),
                invocation.getArgument(2), expiry, expiry.minusNanos(1));
        }).when(codec).issue(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        assertEquals("SESSION_EXPIRED", assertThrows(AuthFailure.class, () -> auth.refresh(token.refreshToken())).body().get("reason"));
        assertEquals(before, jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE session_id=CAST(? AS uuid)", Integer.class, sid));
        assertFalse(jdbc.queryForObject("SELECT consumed_at IS NOT NULL FROM refresh_tokens WHERE refresh_token_hash=?", Boolean.class, AuthService.hash(token.refreshToken())));
    }
    @Test void roleChangeHidesStudentAdmissionInTokenResponse() throws Exception {
        var token = issued(); var user = auth.authenticate(token.accessToken());
        auth.requestAdmission(user.userId(), user.sessionId()); queue.admitWaitingUsers();
        assertTrue(dispatcher.awaitIdle(java.time.Duration.ofSeconds(10)));
        assertTrue(auth.isAdmitted(user.userId(), user.sessionId()));
        jdbc.update("UPDATE users SET role_cd='PROFESSOR' WHERE user_id=?", user.userId());
        var response = mvc.perform(cookiePost("/api/v1/auth/refresh").cookie(new Cookie("__Host-crs_refresh", token.refreshToken())))
            .andExpect(status().isOk()).andExpect(jsonPath("$.user.role").value("PROFESSOR"))
            .andExpect(jsonPath("$.session.admittedAt").value(org.hamcrest.Matchers.nullValue())).andReturn().getResponse();
        OpenApiResponses.verify("/auth/refresh", "post", response);
        assertNotNull(jdbc.queryForObject("SELECT entered_at FROM sessions WHERE session_id=CAST(? AS uuid)", java.sql.Timestamp.class, user.sessionId()));
    }
    private org.springframework.mock.web.MockHttpServletResponse contract(String path, String method,
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, int status) throws Exception {
        var response = mvc.perform(request).andExpect(status().is(status)).andReturn().getResponse();
        OpenApiResponses.verify(path, method, response); return response;
    }
    @Test void allSixAuthOperationsValidateSuccessAndErrorAgainstFrozenOpenApi() throws Exception {
        var login = contract("/auth/login", "post", cookiePost("/api/v1/auth/login").contentType("application/json")
            .content("{\"userId\":\"20240001\",\"password\":\"Demo-password1!\"}"), 202);
        contract("/auth/login", "post", cookiePost("/api/v1/auth/login").contentType("application/json").content("{}"), 400);
        String id = json.readTree(login.getContentAsString()).get("ticketId").asText();
        var proof = new Cookie("__Host-crs_login", login.getCookie("__Host-crs_login").getValue());
        contract("/tickets/{ticketId}", "get", get("/api/v1/tickets/" + id).cookie(proof), 200);
        contract("/tickets/{ticketId}", "get", get("/api/v1/tickets/" + id), 403);
        queue.admitWaitingUsers();
        assertTrue(dispatcher.awaitIdle(java.time.Duration.ofSeconds(10)));
        contract("/tickets/{ticketId}", "get", get("/api/v1/tickets/" + id).cookie(proof), 200);
        var exchanged = contract("/auth/login-tickets/{ticketId}/tokens", "post", cookiePost("/api/v1/auth/login-tickets/" + id + "/tokens").cookie(proof), 200);
        contract("/auth/login-tickets/{ticketId}/tokens", "post", cookiePost("/api/v1/auth/login-tickets/" + id + "/tokens").cookie(proof), 403);
        String access = json.readTree(exchanged.getContentAsString()).get("accessToken").asText();
        var refresh = new Cookie("__Host-crs_refresh", exchanged.getCookie("__Host-crs_refresh").getValue());
        var rotated = contract("/auth/refresh", "post", cookiePost("/api/v1/auth/refresh").cookie(refresh), 200);
        contract("/auth/refresh", "post", cookiePost("/api/v1/auth/refresh"), 401);
        contract("/admission", "post", post("/api/v1/admission").header("Authorization", "Bearer " + access), 202);
        queue.admitWaitingUsers();
        assertTrue(dispatcher.awaitIdle(java.time.Duration.ofSeconds(10)));
        contract("/admission", "post", post("/api/v1/admission").header("Authorization", "Bearer " + access), 200);
        contract("/admission", "post", post("/api/v1/admission"), 401);
        contract("/auth/logout", "post", cookiePost("/api/v1/auth/logout").cookie(
            new Cookie("__Host-crs_refresh", rotated.getCookie("__Host-crs_refresh").getValue())), 204);
        contract("/auth/logout", "post", post("/api/v1/auth/logout"), 403);
    }

}
