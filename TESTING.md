# Build and integration tests

Requires Java 21 and a running Docker engine. Integration tests start a PostgreSQL 17
container through Testcontainers.

## Default build

```sh
./gradlew build
```

```powershell
.\gradlew.bat build
```

The default `test` task runs 22 tests and excludes tests tagged `redis`, so a local Redis
is not required. `BackendApplicationTests` starts its own Redis 7 container through
Testcontainers; the other tests mock `RedissonClient` or exclude Redisson auto-configuration.
Flyway applies `src/main/resources/db/migration/V1__schema.sql` to the Testcontainers
PostgreSQL 17 database. Hibernate uses `ddl-auto: validate` to verify entity mappings
against the migrated schema.

For an existing database that Flyway did not create, back up its data and compare its
schema against V1 before adopting Flyway. Flyway automatic baselining is disabled.
An existing schema that exactly matches V1 can be explicitly baselined at version 1
using Flyway tooling. Resolve schema differences before baselining.
Add future changes as V2 and later migrations instead of editing an applied V1.

## Real Redis integration

Start a dedicated Redis bound to loopback on port 16379, then run `redisTest`.

```sh
docker run -d --name crs-redis-test -p 127.0.0.1:16379:6379 redis:7-alpine
REDIS_TEST_PORT=16379 ./gradlew redisTest
```

```powershell
docker run -d --name crs-redis-test -p 127.0.0.1:16379:6379 redis:7-alpine
$env:REDIS_TEST_PORT = '16379'
.\gradlew.bat redisTest
```

`REDIS_TEST_PORT` defaults to 16379. `redisTest` runs only the two tests tagged `redis`,
using the real Redisson client and scheduled worker.
They verify login ticket processing, proof-protected polling, token exchange, admission,
Redis active-user membership, refresh, logout invalidation, and wrong-password rejection.
The tests use unique queue keys and clean only their own prefix. The SQL database is a
Testcontainers PostgreSQL 17 instance initialized by Flyway, so Docker must also be running.
Redis must be running for this task to pass. Remove the container afterwards with
`docker rm -f crs-redis-test`.

Reports are written to `build/reports/tests/test/index.html` and
`build/reports/tests/redisTest/index.html`.

Browser cookie behavior remains a separate integration check.
