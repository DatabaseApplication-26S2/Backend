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

## 배포 이미지 (Dockerfile)
API 이미지는 저장소 루트의 `Dockerfile`로 만든다. 테스트는 이미지 빌드에서 돌리지 않는다.

```sh
docker build -t crs-api .
```

이미지 안에서는 `application.yml`의 `localhost` 기본값에 닿지 않으므로 실행할 때 아래 환경 변수를 준다. 팀 프로젝트 루트 폴더의 `compose.yaml`(db·redis·api·web)이 이 값을 넣는다.

| 환경 변수 | 예 | 설명 |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `demo` | `demo`는 시드(V900)를 적용한다 |
| `SPRING_DATASOURCE_URL` · `_USERNAME` · `_PASSWORD` | `jdbc:postgresql://db:5432/crs` | PostgreSQL 17 |
| `SPRING_DATA_REDIS_HOST` · `_PORT` | `redis` · `6379` | Redis 7 |
| `AUTH_JWT_SECRET` | `openssl rand -base64 48 \| tr '+/' '-_'` | URL-safe Base64, 32바이트 이상. 비우면 기동할 때마다 키가 바뀐다 |
| `APP_FRONTEND_ORIGIN` | `http://localhost` | 브라우저 출처와 정확히 같아야 한다. 다르면 쿠키 연산이 403 `CSRF_CHECK_FAILED`다 |

## 구성
- 패키지: `com.crs.backend`
- 프로파일: `local`, `test`, `demo`
- DB 스키마: `src/main/resources/db/migration` (Flyway)
- Redis: 대기 순번 사본(`waiting_queue`)이 쓴다. 실행 전에 `redis` 컨테이너를 띄운다.
- 보안 설정: `auth/AuthSecurity`
- 시드: local·demo에서 Flyway V900이 학생 60명·교수 3명·관리자 1명·과목 25개를 만든다. 계정·리셋·팀 연동 범위는 TESTING.md 참고.

## 주의
- 모든 파일은 UTF-8로 저장한다.
- 테스트 방법은 `TESTING.md`를 본다.