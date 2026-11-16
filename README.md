# CRS Backend

## 준비물
- JDK 21
- Docker Desktop (PostgreSQL·Redis 컨테이너, Testcontainers)

## 실행
```powershell
docker compose up -d db redis    # PostgreSQL 17 (포트 5433), Redis 7 (포트 6379)
.\gradlew.bat bootRun            # 기본 프로파일: local
```
- 확인: `curl.exe http://localhost:8080/actuator/health` → 본문에 `"status":"UP"` 포함 (예: `{"groups":[...],"status":"UP"}`)
- 시연: `.\gradlew.bat bootRun --args='--spring.profiles.active=demo'`

## 구성
- 패키지: `com.crs.backend`
- 프로파일: `local`, `test`, `demo`
- DB 스키마: `src/main/resources/db/migration` (Flyway)
- Redis: 대기열과 분산 락이 쓴다. 실행 전에 `redis` 컨테이너를 띄운다.
- 보안 설정: `auth/AuthSecurity`
- 시드: 시연 계정은 `auth/DemoAccounts`가 `demo` 프로파일에서만 만든다. `src/main/resources/db/seed`는 local·demo의 Flyway 위치로만 지정돼 있고 아직 스크립트가 없다.

## 주의
- 모든 파일은 UTF-8로 저장한다.
- 테스트 방법은 `TESTING.md`를 본다.