# Build and integration tests

Requires Java 17.

## Default build

```powershell
.\gradlew.bat build
```

The default build runs 21 tests. Redis calls in the existing integration suite use mocks.
Flyway applies `src/main/resources/db/migration/V1__initial_schema.sql` to the configured H2 database.
Hibernate uses `ddl-auto: validate` to verify entity mappings after migration.
The migration preserves the seven tables and column mappings from the existing entities.

V1 is validated against H2. MySQL or PostgreSQL deployment requires a matching database
configuration and dialect-specific migration validation before use.
The default H2 database remains in memory and is recreated on application restart.

For an existing persistent database created with Hibernate, back up its data and compare
its schema against V1 before adopting Flyway. Flyway automatic baselining is disabled.
An existing schema that exactly matches V1 can be explicitly baselined at version 1
using Flyway tooling. Resolve schema differences before baselining.
Add future changes as V2 and later migrations instead of editing an applied V1.

## Real Redis integration

Start a dedicated local Redis server bound to loopback on port 16379, then run

```powershell
$env:REDIS_TEST_PORT = '16379'
.\gradlew.bat build redisTest
```

`redisTest` runs two additional tests using the real Redisson client and scheduled worker.
They verify login ticket processing, proof-protected polling, token exchange, admission,
Redis active-user membership, refresh, logout invalidation, and wrong-password rejection.
The tests use unique queue keys and clean only their own prefix. The SQL database is H2
initialized by Flyway. Redis must be running for this task to pass.

Reports are written to `build/reports/tests/test/index.html` and
`build/reports/tests/redisTest/index.html`.

Validated locally with Java 17.0.21 and Redis 8.10.3 from the redis-windows Cygwin build,
bound to 127.0.0.1:16379 with persistence disabled. The Windows WSL installation could
not start its virtual machine, so a portable Redis process was used.
Browser cookie behavior and deployment on Linux Redis remain separate integration checks.
