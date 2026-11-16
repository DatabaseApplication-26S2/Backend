package com.project1.enrollment.auth;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Single-process credentials. Restart or expiration deliberately loses credentials.
 * Password arrays are cleared after verification, including exceptional exits.
 */
@Component
public class LoginCredentialStore {
    private static final Duration LIFETIME = Duration.ofMinutes(5);
    private static final int MAX_PENDING = 10000;
    private final Map<String, Credential> pending = new HashMap<>();
    private final Clock clock;

    public LoginCredentialStore() {
        this(Clock.systemUTC());
    }

    LoginCredentialStore(Clock clock) {
        this.clock = clock;
    }

    public synchronized void put(String contextId, String userId, char[] password) {
        purgeExpired();
        if (pending.containsKey(contextId)) {
            throw new IllegalStateException("Credential context already exists");
        }
        if (pending.size() >= MAX_PENDING) {
            throw new IllegalStateException("Login capacity reached");
        }
        pending.put(contextId, new Credential(userId, password.clone(),
                clock.instant().plus(LIFETIME)));
    }

    public <T> T consume(String contextId, Function<Credential, T> verifier,
                         T missingResult) {
        Credential credential;
        synchronized (this) {
            credential = pending.remove(contextId);
        }
        if (credential == null) {
            return missingResult;
        }
        try {
            if (!clock.instant().isBefore(credential.expiresAt)) {
                return missingResult;
            }
            return verifier.apply(credential);
        } finally {
            credential.clear();
        }
    }

    @Scheduled(fixedDelay = 1000)
    public synchronized void purgeExpired() {
        Instant now = clock.instant();
        pending.values().removeIf(credential -> {
            if (!now.isBefore(credential.expiresAt)) {
                credential.clear();
                return true;
            }
            return false;
        });
    }

    public synchronized void discard(String contextId) {
        Credential credential = pending.remove(contextId);
        if (credential != null) {
            credential.clear();
        }
    }

    public static final class Credential {
        private final String userId;
        private final char[] password;
        private final Instant expiresAt;

        private Credential(String userId, char[] password, Instant expiresAt) {
            this.userId = userId;
            this.password = password;
            this.expiresAt = expiresAt;
        }

        public String userId() {
            return userId;
        }

        public char[] password() {
            return password;
        }

        private void clear() {
            Arrays.fill(password, '\0');
        }

        @Override
        public String toString() {
            return "Credential[redacted]";
        }
    }
}
