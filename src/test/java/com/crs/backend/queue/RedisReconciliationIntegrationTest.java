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
    RScoredSortedSet<String> copy;

    @BeforeEach void setup() {
        jdbc.execute("TRUNCATE enrollments,refresh_tokens,sessions,tickets");
        users.save(UserAccount.builder().userId("T10").userName("Mirror test").role(UserRole.STUDENT)
            .passwordHash(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(10).encode("Mirror-password1!")).build());
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
    @Test void t10aLateZaddAfterClaimCannotResurrectCompletedTicket() {
        var ticket = login(); dispatcher.claimAndDispatch();
        copy.add(0, ticket.ticketId()); // Simulate an old afterCommit publication arriving after ZREM.
        reconciler.reconcile(); assertMirror();
        assertEquals("SUCCEEDED", jdbc.queryForObject("SELECT status_cd FROM tickets WHERE ticket_id=?", String.class, Long.parseLong(ticket.ticketId())));
    }
    @Test void t10bMissingPublicationAndWrongScoreAreRepaired() {
        var a = login(); var b = login();
        copy.remove(a.ticketId()); copy.add(-100, b.ticketId());
        reconciler.reconcile(); assertMirror();
        assertEquals(2, copy.size());
    }
    @Test void t10cDeletedKeyIsRestoredByPeriodicReconciliation() {
        login(); login(); copy.delete();
        reconciler.reconcile(); assertMirror(); assertEquals(2, copy.size());
    }
    @Test void t10dNewInstanceRebuildRemovesUnknownMembersAndReloadsWaitingOnly() {
        login(); copy.add(999, "999");
        new RedisWaitingOrderReconciler(redis, jdbc, properties, true).rebuildOnStartup();
        assertMirror(); assertEquals(1, copy.size());
    }
    @Test void t10eUnavailableRedisDoesNotBlockDbAcceptanceProcessingOrPolling() {
        container.getDockerClient().pauseContainerCmd(container.getContainerId()).exec();
        AuthService.LoginReceipt ticket;
        try {
            ticket = login();
            reconciler.reconcile(); // Fail closed for this mirror pass, without failing the application.
            auth.authorizeTicket(Long.parseLong(ticket.ticketId()), ticket.proof(), null);
            assertEquals(1, dispatcher.claimAndDispatch());
            assertEquals("SUCCEEDED", jdbc.queryForObject("SELECT status_cd FROM tickets WHERE ticket_id=?", String.class, Long.parseLong(ticket.ticketId())));
        } finally { container.getDockerClient().unpauseContainerCmd(container.getContainerId()).exec(); }
        reconciler.reconcile(); assertMirror();
        assertNotNull(auth.exchange(Long.parseLong(ticket.ticketId()), ticket.proof()).accessToken());
    }
}
