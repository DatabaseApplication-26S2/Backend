# Build, demo data, and authentication integration

Requires JDK 21 and Docker Desktop running with the Linux/WSL2 engine.

## Run all checks

```powershell
.\gradlew.bat build redisTest
```

Both PostgreSQL 17 and Redis 7 are started on disposable ports by Testcontainers.
No manually installed Redis, database reset, or fixed test port is needed.
The regular test task uses the test profile and excludes the redis tag.
The redisTest task uses the demo profile and logs in with the actual Flyway seed account.

Reports: build/reports/tests/test/index.html and build/reports/tests/redisTest/index.html.

The authentication regression checks cover login 202 → ticket OK → token 200 → admission,
wrong/lost/expired credentials E01, duplicate pending login E03, simultaneous one-time
exchange, simultaneous refresh/reuse revocation, logout cookie priority and Access fallback,
session-specific admission, stale login generation, strict input/CSRF/CORS, response schemas,
and absence of plaintext password/proof/Access/Refresh in database rows and captured logs.

## Demo seed (D-20 provisional values)

local and demo apply db/migration plus db/seed/V900__seed_demo.sql.
test applies only db/migration. Flyway does not overwrite existing data on every startup.

- Students: 20260001 through 20260060.
- Professors: P1001, P1002, P1003.
- Administrator: A1001.
- Shared demo password: Demo-password1!
- Courses: DEMO01 through DEMO25. DEMO01 capacity 3, DEMO02 capacity 1.
- Student 20260001 is preassigned to DEMO03 by A1001 (ADMIN enrollment).

Only the BCrypt cost-10 hash appears in the seed SQL. These public demonstration credentials
are for local/demo databases. D-08: retain Spring Security Nimbus with HS256.
For a persistent deployment, set AUTH_JWT_SECRET to a base64url-encoded random key of at least
32 bytes. Without it, each process generates a new key; restarting invalidates existing Access tokens.

## Demo reset

Stop the backend/workers first. scripts/reset-demo.sql deliberately clears all enrollments,
tickets, sessions, and refresh records in the selected demo DB, resets counts to zero and N to 2,
and retains accounts/courses. It does not recreate the initial ADMIN enrollment.
Check the connection/database name before running it.

```powershell
Get-Content scripts/reset-demo.sql -Raw | docker compose exec -T db psql -U crs -d crs -v ON_ERROR_STOP=1
docker compose exec -T redis redis-cli DEL waiting_queue
```

Use configured queue keys if renamed. Then restart the backend.
DemoSeedTest verifies the seed counts, BCrypt passwords, reset, and enrollment-count invariant
in an isolated PostgreSQL container.

## Shared branch and remaining team integration

This branch incorporates origin/feature/sub-course-admin at a54baa4, including its V1 schema,
common errors, and course endpoints. Those commits retain their original authorship.
An existing DB initialized from the older main V1 has a different checksum/schema.
Do not baseline or clean it automatically. Back it up and migrate deliberately; the test suite
always uses fresh containers. Future frozen-schema changes must use a new migration.

SessionGuard.lock(sessionId) returns a scalar Snapshot under FOR NO KEY UPDATE and requires an
existing transaction. requireStudent(expectedUserId) checks the original session, current role,
revocation and DB-clock expiry. Other queue handlers can reuse this guard.

The branch now incorporates main 3869cde (2026-11-20), including the common TicketIntake,
TicketDispatcher, all four handlers, and BE-17 lease/recovery code. Redis is an ordering mirror;
DB WAITING rows remain authoritative. RedisWaitingOrderReconciler clears/reloads the mirror on
startup and reconciles every 10 seconds, reading Redis before DB and leaving newly published
members outside its observed snapshot untouched. A failed Redis pass is retried next period.
Use AuthService.INTAKE_LOCK (20260001) for shared intake serialization, and preserve the
generation/worker check before consuming login credentials.

## Contract evidence and browser integration

docs/openapi.yaml and src/test/resources/contract/openapi.yaml contain the original from team
Docs b28a9d1, normalized to LF. SHA-256 is
4ad2af2c78eea13aa46aac2bec5ac285c136f6048a538121b29df1547ca3e5bc (checked in tests).
OpenApiResponses.verify(pathTemplate, method, response) is a shared test helper for original
OpenAPI operation/status/content/header checks and JSON Schema 2020-12 response validation.
AuthIntegrationTest covers success/error responses for login, exchange, refresh, logout,
admission and ticket polling. ContractSchemaTest rejects type, enum, range, additional-property
and conditional violations. The remaining 14 operations' full schema checks belong to BE-16 #8b;
smoke-all.sh covers their functional success/error cases but is not a schema validator.
PROVENANCE.txt records the source. The previous reconstructed schema has been removed.

Run ./gradlew.bat build redisTest with Docker running for PostgreSQL and Redis integration tests.
RedisReconciliationIntegrationTest covers late ZADD, missing publication, wrong scores, key deletion,
startup rebuild, and a paused/unavailable Redis while DB intake/processing/polling continue.
AuthIntegrationTest covers rollback at the fractional-second expiry boundary, simultaneous HTTP
refresh (one 200, one 401), current-role projection, and old-generation/missing-memory LOGIN recovery.
See docs/demo-auth-rehearsal.md for S1/S2 scripts, evidence SQL, and interactive S5 lock instructions.

Cookie operations require Origin matching APP_FRONTEND_ORIGIN (default http://localhost:5173)
and X-CSRF-Protection: 1. Frontend requests need credentials: 'include'.
Cookies remain Secure, HttpOnly, SameSite=Lax, Path=/, with __Host- prefixes.
The frontend team's real Chrome session should verify its chosen hostname/HTTPS setup; MockMvc
and Redis integration tests do not simulate a browser cookie store.
