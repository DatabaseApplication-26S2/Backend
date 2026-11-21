package com.crs.backend.queue;

import com.crs.backend.TestcontainersConfiguration;
import com.crs.backend.auth.*;
import com.crs.backend.application.TicketDispatcher;
import com.crs.backend.domain.entity.UserAccount;
import com.crs.backend.domain.enums.UserRole;
import com.crs.backend.adapter.out.persistence.jpa.UserAccountRepository;
import org.junit.jupiter.api.*;
import org.redisson.Redisson;
import org.redisson.api.*;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@Tag("redis")
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(properties = {
    "queue.scheduler-fixed-delay-ms=3600000", "queue.reconcile-enabled=true",
    "queue.reconcile-fixed-delay-ms=3600000",
    "spring.autoconfigure.exclude=org.redisson.spring.starter.RedissonAutoConfigurationV2,org.redisson.spring.starter.RedissonAutoConfigurationV4"
})
@Import({TestcontainersConfiguration.class, RedisReconciliationIntegrationTest.RedisConfig.class})
class RedisReconciliationIntegrationTest {
    @Container static final GenericContainer<?> container = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    @TestConfiguration(proxyBeanMethods=false)
    static class RedisConfig {
        @Bean(destroyMethod="shutdown") RedissonClient redis() {
            Config config = new Config();
            config.useSingleServer().setAddress("redis://" + container.getHost() + ":" + container.getMappedPort(6379))
                .setTimeout(300).setRetryAttempts(0).setConnectionMinimumIdleSize(1).setConnectionPoolSize(2);
            return Redisson.create(config);
        }
    }
    @Autowired RedissonClient redis;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuthService auth;
    @Autowired UserAccountRepository users;
    @Autowired TicketDispatcher dispatcher;
    @Autowired RedisWaitingOrderReconciler reconciler;
    @Autowired QueueProperties properties;
    @Autowired QueueService queue;
    RScoredSortedSet<String> copy;

    @BeforeEach void setup() {
        jdbc.execute("TRUNCATE enrollments,refresh_tokens,sessions,tickets");
        users.save(UserAccount.builder().userId("T10").userName("Mirror test").role(UserRole.STUDENT)
            .passwordHash(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(10).encode("Mirror-password1!")).build());
        jdbc.update("UPDATE service_settings SET concurrency_limit=100");
        copy = redis.getScoredSortedSet(properties.getWaitingKey()); copy.delete();
    }
    AuthService.LoginReceipt login() { return auth.login("T10", "Mirror-password1!".toCharArray()); }
    Set<String> databaseWaiting() {
        return new HashSet<>(jdbc.queryForList("SELECT ticket_id::text FROM tickets WHERE status_cd='WAITING'", String.class));
    }
    void assertMirror() {
        assertEquals(databaseWaiting(), new HashSet<>(copy.readAll()));
        for (String member : copy.readAll()) assertEquals(Double.valueOf(member), copy.getScore(member));
    }
    static final java.time.Duration IDLE = java.time.Duration.ofSeconds(10);
    long id(AuthService.LoginReceipt receipt) { return Long.parseLong(receipt.ticketId()); }
    String status(long id) { return jdbc.queryForObject("SELECT status_cd FROM tickets WHERE ticket_id=?", String.class, id); }
    List<Long> waitingInOrder() { return jdbc.queryForList("SELECT ticket_id FROM tickets WHERE status_cd='WAITING' ORDER BY ticket_id", Long.class); }
    /** Runs one dispatcher pass and waits for its handlers; returns the number of tickets claimed. */
    int claimAndFinish() {
        int claimed = dispatcher.claimAndDispatch();
        assertTrue(dispatcher.awaitIdle(IDLE));
        return claimed;
    }
    void assertAllFinished(long... ids) {
        for (long id : ids) assertFalse(List.of("WAITING", "PROCESSING").contains(status(id)), "ticket not finished: " + id);
    }
    Integer position(long id) { return queue.getTicket(Long.toString(id)).queuePosition(); }

    // T10 (a): Redis order is only a mirror; claim order follows ticket numbers whatever Redis holds.
    @Test void t10a_claimOrderFollowsTicketNumberRegardlessOfRedisOrder() {
        long first = id(login()), second = id(login()), third = id(login());
        jdbc.update("UPDATE service_settings SET concurrency_limit=1");
        copy.add(third, String.valueOf(first)); copy.add(first, String.valueOf(third)); // swapped scores
        copy.remove(String.valueOf(second));                                           // one number missing
        assertEquals(1, claimAndFinish());
        assertEquals(List.of(second, third), waitingInOrder());
        assertEquals(1, claimAndFinish());
        assertEquals(List.of(third), waitingInOrder());
        assertEquals(1, claimAndFinish());
        assertEquals(List.of(), waitingInOrder());
        assertAllFinished(first, second, third);
        reconciler.reconcile(); assertMirror(); assertEquals(0, copy.size());
    }
    // T10 (b): key emptied while running, no intake, no restart: one periodic pass restores it and work then flows.
    @Test void t10b_emptiedKeyIsRestoredByOnePeriodicPassAndTicketsThenFinish() {
        long a = id(login()), b = id(login());
        copy.delete();
        reconciler.reconcile();
        assertMirror(); assertEquals(2, copy.size());
        assertEquals(2, claimAndFinish());
        assertAllFinished(a, b);
        reconciler.reconcile(); assertMirror(); assertEquals(0, copy.size());
    }
    // T10 (c): key deleted, then restart: startup rebuild reloads every WAITING number and tickets finish.
    @Test void t10c_deletedKeyIsRebuiltOnRestartAndTicketsFinish() {
        long a = id(login()), b = id(login());
        copy.delete();
        new RedisWaitingOrderReconciler(redis, jdbc, properties, true).rebuildOnStartup();
        assertMirror(); assertEquals(2, copy.size());
        assertEquals(2, claimAndFinish());
        assertAllFinished(a, b);
        reconciler.reconcile(); assertMirror(); assertEquals(0, copy.size());
    }
    // T10 (d): Redis down during intake and processing; DB path works, one reconcile afterwards matches the mirror.
    @Test void t10d_redisDownDoesNotBlockIntakeOrProcessingAndMirrorMatchesAfterRecovery() {
        container.getDockerClient().pauseContainerCmd(container.getContainerId()).exec();
        AuthService.LoginReceipt ticket;
        try {
            ticket = login();
            reconciler.reconcile(); // Fails this mirror pass without failing the application.
            auth.authorizeTicket(id(ticket), ticket.proof(), null);
            assertEquals(1, claimAndFinish());
            assertEquals("SUCCEEDED", status(id(ticket)));
        } finally { container.getDockerClient().unpauseContainerCmd(container.getContainerId()).exec(); }
        reconciler.reconcile(); assertMirror(); assertEquals(0, copy.size());
        assertNotNull(auth.exchange(id(ticket), ticket.proof()).accessToken());
    }
    // T10 (e): queuePosition = earlier WAITING tickets + 1, whatever Redis holds.
    @Test void t10e_queuePositionIgnoresRedisContents() {
        long a = id(login()), b = id(login()), c = id(login());
        // Earlier WAITING number removed from Redis.
        copy.remove(String.valueOf(a));
        assertEquals(1, position(a)); assertEquals(2, position(b)); assertEquals(3, position(c));
        // The ticket's own number removed from Redis.
        copy.remove(String.valueOf(c));
        assertEquals(3, position(c));
        // Earlier terminated number left in Redis.
        jdbc.update("UPDATE service_settings SET concurrency_limit=1");
        assertEquals(1, claimAndFinish());
        assertAllFinished(a);
        copy.add(a, String.valueOf(a));
        assertEquals(1, position(b)); assertEquals(2, position(c));
        // Closing clause: after the scenarios plus one reconcile no terminated number remains.
        reconciler.reconcile(); assertMirror();
        assertFalse(copy.contains(String.valueOf(a)));
    }

    // Extra coverage.
    @Test void lateZaddAfterClaimCannotResurrectFinishedTicket() {
        var ticket = login();
        assertEquals(1, claimAndFinish());
        copy.add(0, ticket.ticketId()); // Simulate an old afterCommit publication arriving after ZREM.
        reconciler.reconcile(); assertMirror();
        assertEquals("SUCCEEDED", status(id(ticket)));
    }
    @Test void missingPublicationAndWrongScoreAreRepaired() {
        var a = login(); var b = login();
        copy.remove(a.ticketId()); copy.add(-100, b.ticketId());
        reconciler.reconcile(); assertMirror();
        assertEquals(2, copy.size());
    }
    @Test void startupRebuildRemovesUnknownMembersAndReloadsWaitingOnly() {
        login(); copy.add(999, "999");
        new RedisWaitingOrderReconciler(redis, jdbc, properties, true).rebuildOnStartup();
        assertMirror(); assertEquals(1, copy.size());
    }
}
