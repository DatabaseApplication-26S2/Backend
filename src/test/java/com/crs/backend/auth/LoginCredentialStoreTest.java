package com.crs.backend.auth;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class LoginCredentialStoreTest {
    @Test void expiresAtFiveMinutesAndClearsAfterVerification() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-16T00:00:00Z"));
        Clock clock = new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return now.get(); }
        };
        LoginCredentialStore store = new LoginCredentialStore(clock);
        store.put("expired", "student", "password".toCharArray());
        now.set(now.get().plusSeconds(300));
        assertFalse(store.consume("expired", credential -> true, false));
        store.put("valid", "student", "password".toCharArray());
        char[] buffer = store.consume("valid", LoginCredentialStore.Credential::password, null);
        assertArrayEquals(new char[8], buffer);
        assertFalse(store.consume("valid", credential -> true, false));
    }
}
