# CRS Backend — 대기열 기반 수강신청 시스템 API

대기열 기반 수강신청 시스템(CRS)의 백엔드다. Spring Boot 4.1(Java 21)로 만든 REST API이고, 데이터는 PostgreSQL 17에 두며 스키마와 시연 데이터는 Flyway가 적용한다. Redis 7에는 대기 순서의 사본만 둔다(기준은 DB). 브라우저 화면은 Frontend 저장소 <https://github.com/DatabaseApplication-26S2/Frontend> 에 있다. 아래 "전체 스택 실행" 절차를 따르면 이 저장소와 Frontend 저장소로 DB·Redis·API·웹 화면을 한 번에 띄울 수 있다.

두 저장소가 비공개(private)라면 조직(DatabaseApplication-26S2)의 초대를 받은 GitHub 계정이 있어야 클론할 수 있다. 클론할 때 GitHub 로그인(브라우저 인증 또는 개인 액세스 토큰)을 요구한다.

## 전체 스택 실행 (Docker만 있는 PC)

컨테이너 4개(db: PostgreSQL 17, redis: Redis 7, api: 이 저장소, web: Frontend 저장소)를 Docker Compose로 띄운다. JDK, Node.js, PostgreSQL을 PC에 따로 설치할 필요는 없다.

### 1. Docker와 Git 설치

| OS | 설치할 것 |
|---|---|
| Windows 10/11 | Docker Desktop for Windows(WSL 2 기반 엔진), Git for Windows(Git Bash 포함). WSL 2가 없으면 관리자 PowerShell에서 `wsl --install`을 실행하고 재부팅한다 |
| macOS | Docker Desktop for Mac, Git(터미널에서 `git --version`을 처음 실행하면 Command Line Tools 설치를 안내한다) |
| Linux | Docker Engine과 Compose 플러그인(`docker-compose-plugin`), Git. Docker 공식 문서의 배포판별 설치 절차를 따른다 |

- Docker Desktop: <https://www.docker.com/products/docker-desktop/>
- Docker Engine(Linux): <https://docs.docker.com/engine/install/>
- Git: <https://git-scm.com/downloads>

Docker Desktop은 설치 후 실행해 두어야 한다. Linux에서 `docker` 명령에 권한 오류가 나면 명령 앞에 `sudo`를 붙이거나 사용자를 `docker` 그룹에 넣는다.

설치를 확인한다(모든 OS 공통).

```sh
docker version
docker compose version
git --version
```

- `docker version` 출력에 `Server:` 부분이 있어야 한다. 없거나 연결 오류면 Docker Desktop(또는 Docker 서비스)이 꺼져 있는 것이다.
- `docker compose version`이 버전을 출력해야 한다. 하이픈이 들어간 옛 `docker-compose` 명령은 쓰지 않는다.

### 2. 빈 폴더를 만들고 두 저장소 클론

폴더 이름은 무엇이든 된다(아래에서는 `crs`). 클론할 폴더 이름은 반드시 `backend`, `frontend`로 한다. compose 파일이 이 이름으로 빌드 경로를 찾는다.

macOS·Linux(터미널):

```sh
mkdir crs
cd crs
git clone https://github.com/DatabaseApplication-26S2/Backend.git backend
git clone https://github.com/DatabaseApplication-26S2/Frontend.git frontend
```

Windows(PowerShell):

```powershell
mkdir crs
cd crs
git clone https://github.com/DatabaseApplication-26S2/Backend.git backend
git clone https://github.com/DatabaseApplication-26S2/Frontend.git frontend
```

### 3. 전체 스택 compose 파일 저장

전체 스택 compose 파일은 두 저장소 어디에도 들어 있지 않다. 아래 내용을 그대로 복사해 `crs` 폴더에 `compose.yaml`로 저장한다(UTF-8). Windows 메모장으로 저장할 때는 파일 형식을 "모든 파일"로 골라 `compose.yaml.txt`가 되지 않게 한다.

```yaml
# 대기열 기반 수강신청 시스템(CRS) 실행 구성
#   db(PostgreSQL 17) · redis(Redis 7) → api(Spring Boot, ./backend) → web(Caddy, ./frontend)
# 브라우저는 web 하나만 접속한다. web이 /api/*를 api로 넘기므로 화면과 API가 같은 출처이고 CORS가 개입하지 않는다.
#
#   1) .env.example을 .env로 복사하고 JWT_SECRET, POSTGRES_PASSWORD를 채운다(README.md 4장).
#   2) docker compose up -d --build --wait
#   3) Chrome에서 http://localhost 에 접속한다.
name: crs

services:
  db:
    image: postgres:17
    restart: unless-stopped
    environment:
      POSTGRES_DB: crs
      POSTGRES_USER: crs
      # 볼륨을 처음 만들 때만 적용된다. 나중에 바꾸려면 볼륨을 지우고 다시 만든다.
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:?.env에 POSTGRES_PASSWORD를 설정하세요}
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -h 127.0.0.1 -U crs -d crs"]
      interval: 5s
      timeout: 3s
      retries: 20
    volumes:
      - db-data:/var/lib/postgresql/data
    # 포트를 공개하지 않는다. psql은 `docker compose exec db psql -U crs -d crs`로 쓴다.

  redis:
    image: redis:7-alpine
    restart: unless-stopped
    # 대기 순서 사본만 둔다. 기준은 DB이고 api가 기동할 때 DB에서 다시 맞추므로 디스크에 저장하지 않는다.
    command: ["redis-server", "--save", "", "--appendonly", "no"]
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 5s
      timeout: 3s
      retries: 20
    # 포트를 공개하지 않는다.

  api:
    build: ./backend
    restart: unless-stopped
    depends_on:
      db:
        condition: service_healthy
      redis:
        condition: service_healthy
    environment:
      # demo: Flyway가 스키마(V1)와 시연 시드(V900: 계정 64, 과목 25)를 적용한다. 회원가입이 없으므로 시드 없이는 로그인할 수 없다.
      SPRING_PROFILES_ACTIVE: demo
      SPRING_DATASOURCE_URL: jdbc:postgresql://db:5432/crs
      SPRING_DATASOURCE_USERNAME: crs
      SPRING_DATASOURCE_PASSWORD: ${POSTGRES_PASSWORD:?.env에 POSTGRES_PASSWORD를 설정하세요}
      SPRING_DATA_REDIS_HOST: redis
      SPRING_DATA_REDIS_PORT: "6379"
      # 필수. URL-safe Base64로 32바이트 이상. 일반 Base64의 +, /가 섞이면 api가 기동하지 못한다.
      AUTH_JWT_SECRET: ${JWT_SECRET:?.env에 JWT_SECRET을 설정하세요(URL-safe Base64, 32바이트 이상)}
      # 브라우저가 접속하는 출처와 정확히 같아야 한다. 다르면 로그인이 403 E02(CSRF_CHECK_FAILED)다.
      APP_FRONTEND_ORIGIN: ${APP_FRONTEND_ORIGIN:-http://localhost}
    healthcheck:
      test: ["CMD", "wget", "-qO", "/dev/null", "http://127.0.0.1:8080/actuator/health"]
      interval: 10s
      timeout: 3s
      retries: 12
      start_period: 30s
    # 포트를 공개하지 않는다. 외부 요청은 web을 거친다.

  web:
    build: ./frontend
    restart: unless-stopped
    depends_on:
      api:
        condition: service_healthy
    environment:
      API_UPSTREAM: api:8080
    ports:
      - "${WEB_PORT:-80}:80"
    healthcheck:
      test: ["CMD", "wget", "-qO", "/dev/null", "http://127.0.0.1/"]
      interval: 10s
      timeout: 3s
      retries: 6

volumes:
  db-data:
```

파일 머리 주석의 `.env.example`과 `README.md 4장`은 제출 ZIP에 든 파일을 가리킨다. GitHub에서 받을 때는 다음 4단계 명령으로 `.env`를 바로 만든다.

이제 폴더 구조는 다음과 같아야 한다.

```text
crs/
├── compose.yaml
├── .env          (4단계에서 만든다)
├── backend/      (Backend 저장소)
└── frontend/     (Frontend 저장소)
```

`backend/` 안에도 `compose.yaml`이 있지만 그것은 개발 모드용(db·redis만)이다. 전체 스택 명령은 모두 `crs` 폴더(최상위 `compose.yaml`이 있는 곳)에서 실행한다.

### 4. `.env` 만들기

`compose.yaml`과 같은 폴더에서 실행한다. 두 필수 값을 무작위로 만들어 `.env`에 쓴다.

macOS·Linux(터미널):

```sh
cat > .env <<EOF
JWT_SECRET=$(openssl rand -base64 48 | tr '+/' '-_')
POSTGRES_PASSWORD=$(openssl rand -hex 24)
EOF
```

Windows(PowerShell, ASCII로 저장한다):

```powershell
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$b = New-Object byte[] 48; $rng.GetBytes($b)
$jwt = [Convert]::ToBase64String($b).Replace('+','-').Replace('/','_')
$p = New-Object byte[] 24; $rng.GetBytes($p)
$pg = -join ($p | ForEach-Object { $_.ToString('x2') })
Set-Content -Path .env -Encoding ascii -Value "JWT_SECRET=$jwt", "POSTGRES_PASSWORD=$pg"
```

결과 `.env`는 `JWT_SECRET=...`, `POSTGRES_PASSWORD=...` 두 줄이다.

- `JWT_SECRET`은 URL-safe Base64(`+` 대신 `-`, `/` 대신 `_`)여야 한다. 일반 Base64 값을 넣으면 api가 `Illegal base64 character`로 기동하지 못한다.
- `POSTGRES_PASSWORD`는 DB 볼륨을 처음 만들 때만 적용된다. 한 번 띄운 뒤에는 바꾸지 않는다.
- 위 명령은 `.env` 전체를 새로 쓴다. 스택을 한 번이라도 띄웠다면 다시 실행하지 않는다. `POSTGRES_PASSWORD`가 바뀌어 api가 DB에 접속하지 못한다(`password authentication failed`). 직접 더한 `WEB_PORT` 같은 줄도 지워진다. 처음부터 다시 하려면 먼저 `docker compose down -v`(DB 데이터 삭제)를 실행한 뒤 이 명령을 실행한다.
- 80번 포트를 다른 프로그램이 쓰고 있으면 `.env`에 다음 두 줄을 더하고, 브라우저에서 `http://localhost:8080`으로 접속한다.

  ```text
  WEB_PORT=8080
  APP_FRONTEND_ORIGIN=http://localhost:8080
  ```

- 기본 `WEB_PORT`(80)는 PC의 모든 네트워크 인터페이스에서 열린다. 같은 네트워크의 다른 PC도 이 포트에 접속할 수 있고, 공개된 시드 비밀번호로 로그인할 수 있다. 이 PC에서만 접속하게 하려면 `.env`에 `WEB_PORT=127.0.0.1:80`을 넣는다. 이때 `APP_FRONTEND_ORIGIN`은 `http://localhost` 그대로 둔다. 8080번을 쓰면서 이 PC로만 묶으려면 `WEB_PORT=127.0.0.1:8080`, `APP_FRONTEND_ORIGIN=http://localhost:8080`이다.

### 5. 빌드와 기동

모든 OS 공통(`compose.yaml`이 있는 폴더에서):

```sh
docker compose up -d --build --wait
docker compose ps
```

- 첫 빌드는 Docker 이미지와 Gradle·pnpm 의존성을 내려받으므로 인터넷 연결이 필요하고 몇 분 걸린다.
- `--wait`는 모든 컨테이너가 healthy가 되면 끝난다. 오류로 끝나면 아래 "문제 해결"을 본다.
- `docker compose ps`에서 `db`, `redis`, `api`, `web` 네 개의 STATUS가 모두 `(healthy)`여야 한다.

명령줄로 응답을 확인할 수도 있다.

macOS·Linux(터미널):

```sh
curl -s -o /dev/null -w '%{http_code}\n' http://localhost/
curl -s http://localhost/api/v1/courses
```

Windows(PowerShell):

```powershell
curl.exe -s -o NUL -w "%{http_code}\n" http://localhost/
curl.exe -s http://localhost/api/v1/courses
```

- 첫 줄은 `200`을 출력한다(웹 화면).
- 둘째 줄은 토큰 없이 API를 불렀으므로 본문에 `"status":401`과 `"code":"E02"`가 들어 있다. API가 web을 거쳐 응답한다는 뜻이다.

### 6. 브라우저로 접속

Chrome에서 `http://localhost`(포트를 바꿨다면 `http://localhost:8080`)를 연다. 아래 "시연 계정"으로 로그인한다.

- 인증 쿠키는 `__Host-` 접두사가 붙은 `Secure` 쿠키다. 브라우저는 `http://localhost`에서는 이 쿠키를 받지만, localhost가 아닌 주소(다른 PC의 IP, 도메인)로 HTTP 접속하면 로그인 쿠키 `__Host-crs_login`을 버린다. 그래서 로그인이 끝나지 않는다(로그인 티켓 조회가 403 `E02`). 그런 경우는 "서버에 배포할 때"를 본다.
- `http://127.0.0.1`처럼 `APP_FRONTEND_ORIGIN`과 다른 주소로 열면 로그인이 403이 된다. 설정한 주소 그대로 연다.
- localhost에서의 쿠키 동작은 Chrome에서만 확인했으므로 Chrome을 쓴다.

### 제출 ZIP으로 받은 경우

제출 ZIP에는 `backend/`, `frontend/`, `compose.yaml`, `.env.example`, DB 덤프, 별도 안내서가 이미 들어 있다. ZIP을 받았다면 2단계(클론)와 3단계(compose 파일 저장)를 건너뛰고, 압축을 푼 폴더에서 4단계(`.env` 만들기)부터 진행한다. DB 덤프 사용법은 ZIP 안의 안내서를 따른다.

## 시연 계정

api가 `local` 또는 `demo` 프로파일로 처음 기동할 때 Flyway 시드(V900)가 아래 데이터를 만든다. 전체 스택은 `demo` 프로파일로 돈다. 회원가입 기능은 없다.

| 역할 | 아이디 | 비밀번호 | 로그인 후 첫 화면 |
|---|---|---|---|
| 학생 | `20260001`–`20260060` (60명) | `Demo-password1!` | `/courses` (입장 대기열을 통과한 뒤) |
| 교수 | `P1001`, `P1002`, `P1003` | `Demo-password1!` | `/professor/courses` |
| 관리자 | `A1001` | `Demo-password1!` | `/admin/enrollments` |

- 과목: `DEMO01`–`DEMO25`. `DEMO01`은 정원 3명, `DEMO02`는 정원 1명이다.
- 사전 수강: 학생 `20260001`이 `DEMO03`에 관리자 `A1001`의 배정으로 들어가 있다.
- 동시 처리 한도 N의 초깃값은 100이다.
- 비밀번호는 공개된 시연용 값이다. localhost 밖에 공개하는 서버에서는 "서버에 배포할 때"의 "시드 계정 비밀번호 바꾸기"대로 먼저 바꾼다.

Frontend 저장소의 목(mock) 서버(`pnpm dev:mock`, MSW)는 이 백엔드와 별개인 목 전용 데이터를 쓴다(Frontend 저장소의 `src/mocks/seed.ts`). 학생 `20260001`–`20260060`과 교수 `P1001`–`P1003`은 아이디가 같지만 비밀번호가 `crs-demo-1234`이고, 관리자는 `A1001`이 아니라 `A0001`(비밀번호 `crs-demo-1234`)이다. 목 서버에서는 `Demo-password1!`과 `A1001`로 로그인되지 않고, 이 백엔드에서는 `crs-demo-1234`와 `A0001`로 로그인되지 않는다.

## 환경 변수

### 전체 스택 `.env` (compose가 읽는다)

| 키 | 필수 | 기본값 | 설명 |
|---|---|---|---|
| `JWT_SECRET` | 필수 | 없음 | Access JWT(HS256) 서명 키. URL-safe Base64, 디코딩해서 32바이트 이상. api의 `AUTH_JWT_SECRET`으로 들어간다 |
| `POSTGRES_PASSWORD` | 필수 | 없음 | PostgreSQL 비밀번호. DB 볼륨을 처음 만들 때만 적용된다. api의 `SPRING_DATASOURCE_PASSWORD`로도 들어간다 |
| `WEB_PORT` | 선택 | `80` | 웹 화면을 공개할 호스트 포트(web 컨테이너의 80번에 연결). `127.0.0.1:8080`처럼 주소를 붙이면 그 주소에서만 연다 |
| `APP_FRONTEND_ORIGIN` | 선택 | `http://localhost` | 브라우저가 접속하는 출처. 정확히 같아야 한다. 예: `WEB_PORT=8080`이면 `http://localhost:8080` |

### api 컨테이너·프로세스 (Spring Boot)

이미지 안에서는 `application.yml`의 `localhost` 기본값에 닿지 않으므로 아래 값을 환경 변수로 준다. 전체 스택 `compose.yaml`이 이 값을 넣는다. "개발 기본값"은 환경 변수 없이 `bootRun`했을 때 `application.yml`의 값이다.

| 환경 변수 | 전체 스택 값 | 개발 기본값 | 설명 |
|---|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `demo` | `local` | `local`·`demo`는 시드(V900)를 적용하고 `test`는 스키마만 적용한다 |
| `SPRING_DATASOURCE_URL` · `_USERNAME` · `_PASSWORD` | `jdbc:postgresql://db:5432/crs` · `crs` · `.env`의 `POSTGRES_PASSWORD` | `jdbc:postgresql://localhost:5433/crs` · `crs` · `crs` | PostgreSQL 17 |
| `SPRING_DATA_REDIS_HOST` · `_PORT` | `redis` · `6379` | `localhost` · `6379` | Redis 7 |
| `AUTH_JWT_SECRET` | `.env`의 `JWT_SECRET` | 비어 있음 | URL-safe Base64, 32바이트 이상. 비우면 기동할 때마다 무작위 키를 만들어서, 재시작하면 기존 Access 토큰이 무효가 된다 |
| `APP_FRONTEND_ORIGIN` | `.env`의 값(기본 `http://localhost`) | `http://localhost:5173` | 브라우저 출처와 정확히 같아야 한다. 다르면 쿠키 연산이 403 `E02`(`CSRF_CHECK_FAILED`)다 |

## 중지·재시작·초기화

모두 `compose.yaml`이 있는 폴더에서 실행한다(모든 OS 공통).

| 명령 | 동작 |
|---|---|
| `docker compose stop` | 컨테이너를 멈춘다. 데이터는 남는다 |
| `docker compose start` | 멈춘 컨테이너를 다시 시작한다 |
| `docker compose down` | 컨테이너와 네트워크를 지운다. DB 볼륨(데이터)은 남는다 |
| `docker compose down -v` | DB 볼륨까지 지운다. 다음 `docker compose up -d --build --wait`에서 시드를 처음부터 다시 적용한다 |

저장소를 `git pull`로 갱신한 뒤에는 `docker compose up -d --build --wait`로 다시 빌드한다. `git pull`은 이 README에서 복사해 둔 최상위 `compose.yaml`을 갱신하지 않는다. README의 compose 블록이 바뀌었으면 다시 복사해 저장한다.

### 시연 데이터만 되돌리기

계정과 과목은 두고 수강·대기열 상태만 비운다. api를 먼저 멈춘다. PowerShell과 bash에서 같은 명령이다.

```sh
docker compose stop api
docker compose cp backend/scripts/reset-demo.sql db:/tmp/reset-demo.sql
docker compose exec db psql -v ON_ERROR_STOP=1 -U crs -d crs -f /tmp/reset-demo.sql
docker compose exec redis redis-cli DEL waiting_queue
docker compose up -d --wait api
```

`scripts/reset-demo.sql`이 하는 일:

- 수강 내역, 티켓, 세션, Refresh 토큰을 모두 지운다. 로그인해 있던 사용자는 다시 로그인해야 한다.
- 티켓 번호를 1부터 다시 매기고, 과목별 수강 인원을 0으로 되돌린다.
- 동시 처리 한도 N을 2로 둔다(시드 초깃값 100이 아니다).
- 계정과 과목은 그대로 둔다. 시드의 사전 수강(`20260001` → `DEMO03`)은 다시 만들지 않는다.
- 과목 상태(`course_status`)는 건드리지 않는다. 시연 중 폐강한 과목은 `CLOSED`로 남고, 점검 스크립트가 만든 폐강 과목 `SMKCLOSED`도 남는다.
- 마지막 점검 조회가 `(0 rows)`를 출력하면 수강 인원과 실제 수강 내역이 일치한다는 뜻이다.

시드 직후 상태(N = 100, 사전 수강 포함, 시연 중 폐강·추가한 과목 없음)로 완전히 되돌리려면 `docker compose down -v` 후 `docker compose up -d --build --wait`를 실행한다.

## 서버에 배포할 때

Docker가 설치된 Linux 서버에서 같은 `compose.yaml`과 `.env`로 띄운다. 서버에서도 "전체 스택 실행"의 1–4단계(설치, 클론, `compose.yaml` 저장, `.env` 만들기)를 똑같이 한다. localhost가 아닌 주소에서는 HTTPS가 있어야 브라우저 로그인이 된다. 아래 명령은 서버의 bash에서 `compose.yaml`이 있는 폴더에서 실행한다.

시드 계정의 비밀번호는 공개되어 있고, HTTP로도 curl 같은 도구로 `Origin`·`X-CSRF-Protection` 헤더를 붙여 API에 로그인할 수 있다. 그러므로 web 포트를 공개하기 전에 비밀번호를 바꾼다. Linux의 ufw·firewalld는 Docker가 공개한 포트를 막지 못하므로 호스트 방화벽에 기대지 않는다.

1. `.env`에 `APP_FRONTEND_ORIGIN=https://<도메인>`을 넣고, `WEB_PORT`는 리버스 프록시의 위치에 따라 정한다.
   - 프록시가 같은 서버에 있으면 `WEB_PORT=127.0.0.1:8080`을 넣는다. compose의 `"${WEB_PORT:-80}:80"`이 `127.0.0.1:8080:80`이 되어 web은 그 서버 안에서만 닿는다. 프록시가 web으로 보낼 주소는 5단계를 본다.
   - 프록시가 다른 서버에 있으면 `WEB_PORT`를 기본값(또는 다른 포트 번호)으로 두고, 네트워크·하이퍼바이저 방화벽에서 web 포트를 프록시의 IP만 허용하도록 막는다.
2. db, redis, api만 띄운다. 이 단계에서는 어떤 포트도 공개되지 않는다.

   ```sh
   docker compose up -d --build --wait api
   ```

3. 시드 계정의 비밀번호를 바꾼다. 아래 "시드 계정 비밀번호 바꾸기"를 본다.
4. web을 띄운다.

   ```sh
   docker compose up -d --build --wait web
   ```

5. TLS를 종료하는 리버스 프록시(예: Nginx Proxy Manager, Caddy, nginx)를 web 앞에 두고 HTTPS를 강제한다(HTTP 요청은 HTTPS로 리다이렉트). 프록시가 web으로 보낼 주소는 프록시를 실행하는 방식에 따라 다르다.
   - 프록시를 서버에 직접 설치했거나(nginx·Caddy 패키지) 컨테이너를 `network_mode: host`로 실행하면 `http://127.0.0.1:8080`으로 보낸다.
   - 프록시가 일반 Docker 컨테이너이면(예: Nginx Proxy Manager) 그 안의 `127.0.0.1`은 프록시 컨테이너 자신이라 web에 닿지 않는다. 아래 명령으로 프록시 컨테이너를 이 스택의 네트워크(프로젝트 이름 `crs` → 네트워크 `crs_default`)에 붙이고 `http://web:80`으로 보낸다.

     ```sh
     docker network connect crs_default <프록시 컨테이너 이름>
     ```

     `crs_default`에 붙은 프록시 컨테이너는 web뿐 아니라 db(5432), Redis(6379, 인증 없음), api(8080)에도 직접 닿는다. "web만 닿는다"는 경계가 그만큼 넓어지므로 신뢰할 수 있는 프록시 컨테이너에만 쓴다.

     프록시가 붙어 있으면 `docker compose down`이 `crs_default`를 지우지 못한다(사용 중이라는 경고가 나온다). 그래서 `docker compose down` 전에 아래 명령으로 먼저 떼고, 스택을 다시 띄운 뒤 위 `docker network connect` 명령으로 다시 붙인다. 떼지 않고 `down`을 실행했다면 네트워크와 연결이 그대로 남아 있어서, 다시 붙일 때 `already exists` 오류가 난다. 이미 붙어 있다는 뜻이므로 무시한다.

     ```sh
     docker network disconnect crs_default <프록시 컨테이너 이름>
     ```

   - 프록시가 다른 서버에 있으면 `http://<이 서버 주소>:<WEB_PORT>`로 보낸다.
6. 프록시가 `Origin` 헤더를 바꾸지 않게 한다. 바뀌면 로그인이 403 `CSRF_CHECK_FAILED`다.
7. 프록시에 Basic 인증을 걸지 않는다. 앱이 `Authorization: Bearer` 헤더를 쓰므로 충돌한다.
8. db, redis, api의 포트는 공개하지 않는다(`compose.yaml` 그대로 두면 web만 포트를 연다).

### 시드 계정 비밀번호 바꾸기

시드 계정 64개(관리자 `A1001` 포함)는 모두 공개된 비밀번호 `Demo-password1!`를 쓴다. 비밀번호 변경 API는 없으므로 DB에 BCrypt 해시를 직접 넣는다. 해시는 `htpasswd`(Apache 유틸리티)로 만든다.

먼저 `htpasswd`를 설치한다(Debian·Ubuntu, 이미 있으면 건너뛴다).

```sh
sudo apt-get install -y apache2-utils
```

서버의 bash에서, `compose.yaml`이 있는 폴더에서 관리자 비밀번호를 바꾼다. 입력한 비밀번호는 화면에 보이지 않고 셸 기록이나 명령 인자에 남지 않는다.

```sh
read -rsp '새 비밀번호: ' NEWPW; echo
HASH=; [ -n "$NEWPW" ] && HASH=$(printf '%s' "$NEWPW" | htpasswd -niBC 10 '' | tr -d ':\n'); unset NEWPW
[ -n "$HASH" ] && docker compose exec db psql -U crs -d crs -c "UPDATE users SET password_hash = '$HASH' WHERE user_id = 'A1001'"
```

- 출력이 `UPDATE 1`이면 바뀐 것이다. 이후 새 비밀번호로 로그인되고 `Demo-password1!`은 거부된다.
- `HASH=`는 이전에 실행했던 해시 값을 먼저 비운다. 비밀번호를 입력하지 않고 Enter만 누르면 해시를 만들지 않고, `[ -n "$HASH" ] &&`가 해시가 비었을 때 UPDATE를 실행하지 않게 한다. 빈 비밀번호의 해시나 빈 `password_hash`가 들어가면 그 계정은 로그인할 수 없게 잠긴다.
- `htpasswd`가 없으면 bash가 `htpasswd: command not found`를 출력하고 `UPDATE` 줄은 나오지 않는다. `apache2-utils`를 설치하고 세 줄을 다시 실행한다. Enter만 눌렀을 때도 `UPDATE` 줄이 나오지 않으므로 다시 실행한다.
- 교수 3명과 학생 60명은 `WHERE` 조건만 바꿔 실행한다. 관리자와 같은 비밀번호면 `$HASH`를 그대로 쓴다. 다른 비밀번호를 쓰려면 그 전에 위의 `read`와 `HASH=` 두 줄을 다시 실행한다.

  ```sh
  [ -n "$HASH" ] && docker compose exec db psql -U crs -d crs -c "UPDATE users SET password_hash = '$HASH' WHERE role_cd = 'PROFESSOR'"   # UPDATE 3
  [ -n "$HASH" ] && docker compose exec db psql -U crs -d crs -c "UPDATE users SET password_hash = '$HASH' WHERE role_cd = 'STUDENT'"     # UPDATE 60
  ```

- 새 비밀번호는 72바이트 이하(영문·숫자 기준 72자 이하)로 정한다. BCrypt는 72바이트까지만 쓴다.
- `read`는 입력 앞뒤의 공백을 지운다. 비밀번호 앞뒤에 공백을 넣지 않는다.
- `htpasswd`가 만드는 `$2y$` 형식 해시를 그대로 쓴다.
- `docker compose down -v`로 DB 볼륨을 지우면 다음 기동에서 시드가 다시 들어가 비밀번호가 `Demo-password1!`로 돌아간다. 그때는 이 절("서버에 배포할 때")의 2–4단계대로 api까지만 띄우고(`docker compose up -d --build --wait api`) 이 절차를 다시 실행한 뒤 web을 띄운다.
- 바꾼 뒤 점검 스크립트를 돌리는 조건은 "테스트와 점검 스크립트"의 "전체 스택 점검(smoke) 스크립트"를 본다.

## 개발 모드 실행

API를 컨테이너 없이 Gradle로 직접 띄운다. JDK 21과 Docker가 필요하다. Gradle은 저장소의 래퍼(`gradlew`)가 내려받는다. 아래 명령은 이 저장소 폴더(`backend`)에서 실행하며, 이 폴더의 `compose.yaml`(개발용: db·redis만)을 쓴다.

macOS·Linux:

```sh
docker compose up -d --wait db redis    # PostgreSQL 17(호스트 포트 5433), Redis 7(포트 6379)
./gradlew bootRun                       # 기본 프로파일: local
```

Windows(PowerShell):

```powershell
docker compose up -d --wait db redis    # PostgreSQL 17(호스트 포트 5433), Redis 7(포트 6379)
.\gradlew.bat bootRun                   # 기본 프로파일: local
```

- 이 폴더의 `compose.yaml`은 PostgreSQL(호스트 포트 5433, 비밀번호 `crs`)과 Redis(포트 6379, 인증 없음)를 PC의 모든 네트워크 인터페이스에 공개한다. 같은 네트워크의 다른 PC도 접속할 수 있으므로 신뢰할 수 있는 네트워크에서만 쓴다.
- `--wait`는 db 컨테이너의 healthcheck(`pg_isready`)가 통과할 때까지 기다린 뒤 끝난다. 그 뒤에 `bootRun`을 실행해야 Flyway가 PostgreSQL 준비 전에 접속하지 않는다. 처음 띄울 때 `bootRun`이 DB 연결 오류로 멈추면 한 번 더 실행한다.
- `local` 프로파일은 시드(V900)를 적용하고, Frontend 저장소의 개발 서버 출처 `http://localhost:5173`을 허용한다(`APP_FRONTEND_ORIGIN` 기본값).
- API는 `http://localhost:8080`에서 뜬다. 확인: `curl http://localhost:8080/actuator/health`(Windows는 `curl.exe`) → 본문에 `"status":"UP"`이 들어 있다.
- `demo` 프로파일로 띄우기: `./gradlew bootRun --args='--spring.profiles.active=demo'` (Windows는 `.\gradlew.bat bootRun --args='--spring.profiles.active=demo'`).
- `AUTH_JWT_SECRET`을 주지 않으면 기동할 때마다 무작위 키를 만든다. 재시작하면 다시 로그인해야 한다.
- macOS·Linux에서 `./gradlew`가 권한 오류를 내면 `chmod +x gradlew`를 실행한다.
- 개발 DB의 시연 데이터를 되돌릴 때는 `bootRun`을 멈추고 이 폴더에서 다음을 실행한 뒤 다시 `bootRun`한다.

  ```sh
  docker compose cp scripts/reset-demo.sql db:/tmp/reset-demo.sql
  docker compose exec db psql -v ON_ERROR_STOP=1 -U crs -d crs -f /tmp/reset-demo.sql
  docker compose exec redis redis-cli DEL waiting_queue
  ```

## API 이미지만 빌드

API 이미지는 저장소 루트의 `Dockerfile`로 만든다(모든 OS 공통, 이 저장소 폴더에서).

```sh
docker build -t crs-api .
```

- 테스트는 이미지 빌드에서 돌리지 않는다(Testcontainers가 Docker를 필요로 하기 때문). 테스트는 아래 절에서 따로 돌린다.
- 1단계(`eclipse-temurin:21-jdk-alpine`)에서 `bootJar`로 JAR을 만들고, 2단계(`eclipse-temurin:21-jre-alpine`)에서 비루트 사용자 `crs`로 8080 포트에서 실행한다.
- JWT 서명 키와 DB 비밀번호는 이미지에 넣지 않는다. 실행할 때 위 "api 컨테이너·프로세스" 표의 환경 변수를 준다.

## 테스트와 점검 스크립트

### 자동 테스트

Docker가 켜져 있어야 한다. Testcontainers가 PostgreSQL 17과 Redis 7을 임시 포트로 띄운다.

```sh
./gradlew build redisTest          # macOS·Linux
```

```powershell
.\gradlew.bat build redisTest      # Windows
```

- `test` 작업은 `redis` 태그가 없는 테스트를 돌린다. 통합 테스트는 `test` 프로파일(스키마만, 시드 없음)로 뜬다.
- `redisTest` 작업은 `redis` 태그가 붙은 두 테스트를 돌린다. `RealRedisIntegrationTest`는 `demo` 프로파일과 실제 시드 계정을 쓰고, `RedisReconciliationIntegrationTest`는 `test` 프로파일(시드 없음)을 쓴다.
- 결과 보고서: `build/reports/tests/test/index.html`, `build/reports/tests/redisTest/index.html`.
- 자세한 범위는 `TESTING.md`(영문)를 본다.

### 전체 스택 점검(smoke) 스크립트

전체 스택을 띄운 상태에서 1단계 API 연산 12개의 성공과 대표 오류를 HTTP로 확인한다. bash와 curl이 필요하다. Windows에서는 PowerShell이 아니라 Git Bash 또는 WSL에서 실행한다. `compose.yaml`이 있는 폴더에서:

```sh
ORIGIN=http://localhost BASE=http://localhost/api/v1 bash backend/scripts/smoke-p1.sh
```

- 모두 통과하면 마지막 줄이 `모든 점검 통과: 27/27`이고 종료 코드는 0이다. 하나라도 실패하면 실패 목록을 출력하고 종료 코드 1로 끝난다.
- 기본값으로 시드 비밀번호 `Demo-password1!`를 써서 학생 `20260003`, 교수 `P1001`, 관리자 `A1001`로 로그인한다. 그래서 "시드 계정 비밀번호 바꾸기"를 실행하기 전에 돌린다. 바꾼 뒤에는 이 세 계정이 모두 같은 새 비밀번호일 때만 환경 변수 `PASSWORD=<새 비밀번호>`를 더해 실행할 수 있다.
- `WEB_PORT`를 바꿨다면 두 값도 바꾼다. 예: `ORIGIN=http://localhost:8080 BASE=http://localhost:8080/api/v1`.
- 배포한 서버에서 돌릴 때는 서버의 bash에서 `compose.yaml`이 있는 폴더에서 실행한다. 서버는 `APP_FRONTEND_ORIGIN=https://<도메인>`으로 돌므로 로컬 포트로 부르더라도 `ORIGIN`은 그 값이어야 한다(Origin 검사는 헤더 문자열만 비교한다). 예(`WEB_PORT=127.0.0.1:8080`인 경우):

  ```sh
  ORIGIN=https://<도메인> BASE=http://127.0.0.1:8080/api/v1 PASSWORD=<새 비밀번호> bash backend/scripts/smoke-p1.sh
  ```

  명령줄에 쓴 비밀번호는 셸 기록에 남는다. 남기지 않으려면 먼저 `read -rsp '비밀번호: ' PASSWORD; echo; export PASSWORD`를 실행하고 위 명령에서 `PASSWORD=<새 비밀번호>`를 뺀다. 점검이 끝나면 `unset PASSWORD`를 실행해 비밀번호를 셸 환경에서 지운다.

- 처음 실행할 때 교수 `P1001` 담당의 폐강 과목 `SMKCLOSED`를 API로 만든다(시드에 폐강 과목이 없어서). 이 과목은 지우지 않고 남으므로, 공개 서버에서 실행하면 실제 데이터에 `SMKCLOSED`가 생긴다. 스크립트가 만든 수강 내역은 마지막에 스스로 취소하므로 여러 번 실행할 수 있다.
- `BASE`와 `ORIGIN`을 주지 않으면 개발 모드 기본값(`http://localhost:8080/api/v1`, Origin `http://localhost:5173`)을 대상으로 한다. 개발 모드에서는 이 저장소 폴더에서 `bash scripts/smoke-p1.sh`로 실행한다.

## 구성

- 패키지: `com.crs.backend` (`src/main/java/com/crs/backend`)
- 프로파일: `local`(기본, 스키마 + 시드), `test`(스키마만), `demo`(스키마 + 시드, 전체 스택에서 사용)
- DB 스키마: `src/main/resources/db/migration` (Flyway, `V1__schema.sql`). JPA는 테이블을 만들지 않고 검사만 한다.
- 시드: `src/main/resources/db/seed/V900__seed_demo.sql` (`local`·`demo`에서만 적용. 비밀번호는 BCrypt 해시로만 들어 있다)
- Redis: 대기 순번 사본(`waiting_queue`)만 둔다. 기준은 DB의 대기 티켓이고, api가 기동할 때와 10초마다 DB 기준으로 다시 맞춘다. 실행 전에 Redis가 떠 있어야 한다.
- 보안 설정: `auth/AuthSecurity` (쿠키 연산의 Origin 검사, Bearer 토큰 인증)
- 상태 확인: `/actuator/health`만 공개한다.
- API 계약: `src/test/resources/contract/openapi.yaml` (Frontend 저장소의 `openapi.yaml`과 같은 파일)

## 문제 해결

로그는 `compose.yaml`이 있는 폴더에서 `docker compose logs api`(또는 `db`, `web`)로 본다.

| 증상 | 원인 | 해결 |
|---|---|---|
| `up` 직후 `.env에 JWT_SECRET을 설정하세요` 같은 오류 | `.env`가 `compose.yaml`과 같은 폴더에 없거나 값이 비었다(Windows에서는 `.env.txt`로 저장된 경우도 있다) | "4. `.env` 만들기"를 같은 폴더에서 다시 실행한다 |
| `up` 중 80번 포트를 열 수 없다는 오류(포트 사용 중) | 다른 프로그램이 80번 포트를 쓴다(Windows에서 흔하다) | `.env`에 `WEB_PORT=8080`, `APP_FRONTEND_ORIGIN=http://localhost:8080`을 넣고 `docker compose up -d --build --wait`, 브라우저는 `http://localhost:8080` |
| 로그인이 HTTP 403, 응답 본문 `code`가 `E02`, `reason`이 `CSRF_CHECK_FAILED` | 브라우저 출처(예: `http://127.0.0.1`, 다른 포트)가 `APP_FRONTEND_ORIGIN`과 다르다 | `APP_FRONTEND_ORIGIN`에 적은 주소 그대로 접속하거나, `.env`를 고치고 `docker compose up -d --wait` |
| `up --wait`가 실패하고 api가 healthy가 되지 않는다. `docker compose logs api`에 `Illegal base64 character` | `JWT_SECRET`에 일반 Base64 문자 `+`, `/`가 있다 | `JWT_SECRET`은 DB와 무관하므로 데이터를 지울 필요가 없다. `.env`의 `JWT_SECRET=` 줄만 새 값으로 바꾸고(macOS·Linux: `openssl rand -base64 48 \| tr '+/' '-_'`의 출력, Windows: 4단계 PowerShell 명령의 1–3줄을 실행한 뒤 `$jwt`의 값) `docker compose up -d --wait`를 실행한다. api가 새 키로 다시 만들어지고 아직 뜨지 않은 web도 뜬다. 처음부터 다시 하려면 `docker compose down -v`(DB 데이터 삭제) 후 4단계와 `docker compose up -d --build --wait` |
| api 로그에 `JWT secret must contain at least 32 bytes` | `JWT_SECRET`이 너무 짧다 | 위 행과 같다. `.env`의 `JWT_SECRET=` 줄만 48바이트 새 값으로 바꾸고 `docker compose up -d --wait` |
| api 로그에 `password authentication failed` | DB 볼륨을 만든 뒤 `.env`의 `POSTGRES_PASSWORD`를 바꿨다 | 원래 값으로 되돌린다. 데이터를 버려도 되면 `docker compose down -v` 후 `docker compose up -d --build --wait` |
| 빌드가 `Killed` 또는 exit code 137로 멈춘다 | Docker에 할당된 메모리가 부족하다 | Docker 메모리를 4 GB 이상으로 늘리고 다시 빌드한다. macOS: Docker Desktop 설정 > Resources > Memory. Windows(WSL 2): `%UserProfile%\.wslconfig`의 `[wsl2]` 아래에 `memory=4GB`를 쓰고 `wsl --shutdown` 후 Docker Desktop을 다시 켠다 |
| `http://localhost`에서는 로그인되는데 다른 PC나 IP 주소로 HTTP 접속하면 로그인이 끝나지 않는다(로그인 티켓 조회가 403 `E02`) | 인증 쿠키가 `Secure`라서 브라우저가 HTTP에서는 로그인 쿠키 `__Host-crs_login`을 버린다. localhost 밖에서는 HTTPS가 필요하다 | "서버에 배포할 때"대로 HTTPS 리버스 프록시를 두고 `APP_FRONTEND_ORIGIN=https://<도메인>`으로 설정한다 |

## 주의

- 모든 파일은 UTF-8로 저장한다. `.env`를 손으로 고칠 때도 UTF-8(또는 ASCII)로 저장한다.
- `.env`에는 비밀 값(JWT 서명 키, DB 비밀번호)이 들어간다. 커밋하거나 공유하지 않는다. `.env`는 `backend/`·`frontend/` 저장소 밖(최상위 폴더)에 두고 저장소 안으로 복사하지 않는다.
