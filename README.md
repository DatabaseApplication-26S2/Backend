# CRS Backend

## 준비물
- JDK 21
- Docker Desktop

## 실행
```powershell
docker compose up -d db          # PostgreSQL 17 (포트 5433)
.\gradlew.bat bootRun            # 기본 프로파일: local
```
- 확인: `curl.exe http://localhost:8080/actuator/health` → `{"status":"UP"}`
- 시연: `.\gradlew.bat bootRun --args='--spring.profiles.active=demo'`

## 구성
- 패키지: `com.crs.backend`
- 프로파일: `local`, `test`, `demo`
- DB 스키마: `src/main/resources/db/migration` (Flyway)
- 시드: `src/main/resources/db/seed` (local·demo만 적용)

## 주의
- 모든 파일은 UTF-8로 저장한다.
- `SecurityConfig`는 임시본이다. BE-02(팀원2)에서 교체한다.