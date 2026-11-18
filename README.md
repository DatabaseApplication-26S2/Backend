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
- 시드: local·demo에서 Flyway V900이 학생 60명·교수 3명·관리자 1명·과목 25개를 만든다. 계정·리셋·팀 연동 범위는 TESTING.md 참고.

## 주의
- 모든 파일은 UTF-8로 저장한다.
- 테스트 방법은 `TESTING.md`를 본다.