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
docker compose exec -T redis redis-cli DEL waiting_queue active_users
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

The common TicketIntake/TicketHandler interfaces are still absent from the available team code.
The current LOGIN/ADMISSION adapter in QueueService selects DB WAITING rows in ticket order,
checks the shared N limit, and runs AuthService.processLogin/processAdmission transactionally.
Redis is a post-commit ordering mirror for these types; failed publication cannot strand them.
This adapter is intentionally not the team's full asynchronous dispatcher/recovery implementation.
The course enrollment/cancellation queue path and multi-instance recovery remain BE-03/04/08/17
integration work with the queue owner. Use AuthService.INTAKE_LOCK (20260001) for shared intake
serialization, and preserve the generation/worker check before consuming login credentials.

## Contract evidence and browser integration

src/test/resources/contract/notion-schemas.json reconstructs 47 schemas from the supplied Notion
schema blocks and property tables, including conditional constraints. Networknt validates actual
authentication responses and rejects invalid types, enums, ranges, extra fields, and conditions.
PROVENANCE.txt records the source. This is not a frozen original openapi.yaml: the team Docs
repository was empty when checked. Final equivalence to that original remains a team check.

Cookie operations require Origin matching APP_FRONTEND_ORIGIN (default http://localhost:5173)
and X-CSRF-Protection: 1. Frontend requests need credentials: 'include'.
Cookies remain Secure, HttpOnly, SameSite=Lax, Path=/, with __Host- prefixes.
The frontend team's real Chrome session should verify its chosen hostname/HTTPS setup; MockMvc
and Redis integration tests do not simulate a browser cookie store.
