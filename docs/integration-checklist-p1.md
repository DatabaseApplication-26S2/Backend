# X-05 1단계 통합 점검표

| 항목 | 내용 |
|---|---|
| 작업 | X-05 1단계 통합(구현계획-backend.md 6.3절), 담당 백진현 |
| 마일스톤 | M2(11/18) |
| 완료 조건 | `scripts/smoke-p1.sh` exit 0, 브라우저 S1~S3 통과 |
| 점검 대상 | 1단계 연산 12개의 성공과 대표 오류(구현계획-backend.md 7절 단계별 필수) |

## 1. 실행 환경

| 항목 | 값 |
|---|---|
| 백엔드 | `docker compose up -d db redis` → `.\gradlew.bat bootRun`(local) |
| 프런트엔드 | `pnpm dev` → http://localhost:5173 (`VITE_USE_MOCK=false`, Vite 프록시 `/api` → 8080) |
| 테스트 데이터 | 11/20부터 V900 시연 시드(local·demo 프로파일이 자동 적용): 학생 `20260001`~`20260060`, 교수 `P1001`~`P1003`, 관리자 `A1001`(비밀번호 공통), 과목 `DEMO01`~`DEMO25`. 11/18 실행 기록은 당시 로컬 테스트 데이터(`prof01`·`admin01`) 기준 |
| 자동 점검 | Git Bash에서 `bash scripts/smoke-p1.sh` |

## 2. 연산별 점검(smoke-p1.sh)

결과 열은 실행할 때마다 채운다. ✅ 통과 · ❌ 실패 · ⚠️ 계약과 다름(담당자 확인 필요)

**실행 기록:** 2026-11-18 11:33, `feature/sub-course-admin`, local 프로파일 — **27/27 통과(exit 0)**

**실행 기록(#9 기본값 변경 후):** 2026-11-20 20:27, `feature/sub-course-admin-m5`, local 프로파일·V900 시드(새 DB), 환경 변수·보조 SQL 없이 — **27/27 통과(exit 0), 연속 2회**

| # | 연산 | 담당 | 성공 확인 | 대표 오류 확인 | 결과 |
|---|---|---|---|---|---|
| 1 | `POST /auth/login` | 김재섭님 | 202, `Location`, `Retry-After`, `__Host-crs_login` 쿠키 | 본문 누락 → 400 E09 | ✅ |
| 2 | `GET /tickets/{id}` | 기태님 | 소유 증명 쿠키로 조회 → 200, 결과 OK | 쿠키 없이 LOGIN 티켓 → 403 E02 | ✅ |
| 3 | `POST /auth/login-tickets/{id}/tokens` | 김재섭님 | 200, `accessToken`·`user`·`session`, Refresh 쿠키 | 두 번째 수령 → 403 `LOGIN_EXCHANGE_CONSUMED` | ✅ |
| 4 | `POST /admission` | 김재섭님 | 202 → 입장 후 재요청 200 `{admittedAt}` | 교수 → 403 E02 | ✅ |
| 5 | `GET /courses` | 백진현 | 200, `pageSize` 20, `no-store`, 학생 `enrolled` | `page=0` → 400 E09 | ✅ |
| 6 | `POST /me/enrollments` | 기태님 | 202 + `Location`, 티켓 결과 OK | 없는 과목 → 404 E04 | ✅ |
| 7 | `POST /admin/enrollments` | 백진현 | 201, `enrollmentType` ADMIN | 폐강 과목 → 409 E05 | ✅ |
| 8 | `GET /me/enrollments` | 백진현 | 200, 학생 신청·관리자 배정 모두 포함 | 교수 → 403 E02 | ✅ |
| 9 | `DELETE /me/enrollments/{code}` | 기태님 | 202, 티켓 결과 OK(관리자 배정 행도 취소) | 없는 과목 → 404 E04 | ✅ |
| 10 | `GET /professor/courses` | 백진현 | 200, 폐강 과목 포함 | 학생 → 403 E02 | ✅ |
| 11 | `PUT /professor/courses/{code}` | 백진현 | 200 수정된 과목 | 남의 과목 → 403 `FORBIDDEN` | ✅ |
| 12 | `POST /auth/logout` | 김재섭님 | 204 | 로그아웃한 Access → 401 | ✅ |

## 3. 브라우저 시나리오

S1~S3의 정의는 구현계획.md#verification(9.2 발표 시나리오)을 따른다. 실행 기록과 화면 밖 증거는 6절에 있다.

| 시나리오 | 내용(구현계획.md 9.2) | 확인한 사람 | 결과 | 비고 |
|---|---|---|---|---|
| S1 | N=2에서 5명이 동시에 로그인. 티켓 번호 순으로 claim, `PROCESSING` ≤ 2, 완료 순서는 달라도 됨 | 백진현 | ✅ | 동시 5명은 `scripts/scenario-s1.sh`, Chrome에서 1명 로그인·대기 화면 확인 |
| S2 | 정원 3 과목에 30명이 동시에 신청 → OK 3건, E07 27건, INV-2 0행 | 백진현 | ✅ | 동시 30명은 `scripts/scenario-s2.sh`, Chrome에서 31번째 신청 → E07 화면 |
| S3 | 관리자 초과 배정, 학생 E07, 학생 취소, 교수 정원 축소 E11, 학생의 관리자 API 403 | 백진현 | ✅ | 모두 Chrome. E11은 `DEMO01`(정원 3·인원 3 → 2) |

### 3.1 화면별 확인 항목(참고)

| 역할 | 화면 | 확인 내용 |
|---|---|---|
| 학생 | 로그인 → 입장 대기 | 대기 후 과목 화면으로 이동 |
| 학생 | 과목 검색 | 23개, 2페이지, 과목코드 순, 폐강 표시, 검색어 `50%` → 1건, 신청 여부 표시 |
| 학생 | 수강 신청·취소 | 대기 후 결과 표시, 여석 변화 |
| 학생 | 내 신청 내역 | 관리자 배정 과목 포함 |
| 교수 | 담당 과목·수정 | 4개(폐강 포함), 강의실 수정 반영, 최소 인원 > 정원 오류, 폐강 과목 수정 오류 |
| 관리자 | 학생 배정 | 정원 초과 표시, 중복 E06, 폐강 E05, 학생 아님 E13 |

## 4. 발견한 문제

| 날짜 | 연산·화면 | 내용 | 담당 | 상태 |
|---|---|---|---|---|
| 11/18 | `POST /auth/*`(Origin 검사) | 프록시가 넘기는 Origin(5173)을 백엔드 주소(8080)와 비교해 모든 POST가 403 | 김재섭님 | 수정함(`app.frontend-origin`과 비교, `CSRF_CHECK_FAILED`) |
| 11/18 | `POST /auth/login-tickets/{id}/tokens` | 응답에 `user`·`session`이 없어 로그인 후 화면 이동이 멈춤 | 김재섭님 | 수정함 |
| 11/18 | `POST /auth/login` | 202 접수증에 `Retry-After: 2` 헤더가 없음(계약 5절) | 김재섭님 | 수정함 |
| | | | | |

## 5. 인증 브랜치 통합 재검증 (2026-11-18)

- 통합 기준: `feature/auth-login`의 `fbd6026` + 백진현님 브랜치 `21a0018`.
- 환경: Java 21, PostgreSQL 17 / Redis 7 일회용 컨테이너, demo 프로파일, 백엔드 `127.0.0.1:18080`.
- 기존 팀 DB는 사용하지 않았다. V900 시드에 `scripts/smoke-auth-demo.sql`의 보조 과목 2개를 한 번 추가했다.
- 첫 실행: 24/27 통과. 교수의 입장/내역 조회, 학생의 교수 과목 조회 3건에서 HTTP 403이었지만 오류 JSON 파싱 실패.
- 원인/수정: `AuthSecurity` 필터 오류가 ISO-8859-1로 출력돼 한글 중간점 등이 UTF-8 파싱을 깨뜨림. 응답 인코딩을 UTF-8로 지정하고 실제 응답 바이트와 한글 제목 보존을 회귀 테스트에 추가했다.
- 수정 후 재실행: **27/27 통과, exit 0**. 로그인 접수/조회/교환, 세션 입장, 역할별 권한, 신청/배정/취소, 교수 수정, 로그아웃 무효화까지 확인했다.
- 스크립트 실패 출력에서 응답 본문을 제거했다. 토큰 수령 응답의 검증 실패가 Access 토큰을 로그에 남기지 않도록 했다.
- `build redisTest`: **73개 일반 테스트 + 2개 실제 Redis 테스트 통과**. UTF-8 회귀 검증은 기존 권한 테스트에 추가했다.

### V900 시드를 이용한 재현 설정

먼저 일회용 DB에 demo 프로파일로 기동하고 `scripts/smoke-auth-demo.sql`을 한 번 실행한다.
그 후 Git Bash에서 아래 환경 변수를 지정하면 원래 smoke 시나리오 27개를 그대로 실행한다.
`BASE`는 실제로 띄운 테스트 서버 주소로 변경한다.

```bash
BASE=http://127.0.0.1:18080/api/v1 \
ORIGIN=http://localhost:5173 \
PASSWORD='Demo-password1!' STUDENT=20260003 PROFESSOR=P1001 ADMIN=A1001 \
COURSE_ENROLL=DEMO02 COURSE_ASSIGN=DEMO03 COURSE_CLOSED=SMKCLOSED COURSE_OTHERS=DEMO02 \
bash scripts/smoke-p1.sh
```

> 11/20(#9)부터 smoke 기본값이 V900 시드다. 위 환경 변수와 `smoke-auth-demo.sql` 없이 `bash scripts/smoke-p1.sh`만 실행한다.
> 폐강 과목 `SMKCLOSED`는 처음 실행할 때 스크립트가 교수 `P1001`로 등록·폐강해 준비하고, 교수 수정 [11]은 `DEMO04`를 현재 값 그대로 다시 저장한다.

이 기록은 직접 HTTP smoke 실행 결과다. Vite 프록시 및 실제 브라우저 쿠키 저장/전송,
위 S1~S3, 공용 TicketIntake/TicketHandler 최종 연동의 완료를 의미하지 않는다.

## 6. #9 브라우저 S1~S3 실행 기록 (2026-11-20)

- 환경: `feature/sub-course-admin-m5`(main `3237fd9` + BE-14), `.\gradlew.bat bootRun`(local, V900 시드), Frontend `24c36d4` `pnpm dev`(http://localhost:5173, Vite 프록시 `/api` → 8080), Chrome(Claude in Chrome로 조작).
- 시작 전 `scripts/reset-demo.sql`(내역·티켓·세션 비움, N=2)과 `redis-cli DEL waiting_queue`로 초기화했다.

### S1 — N=2에서 5명 동시 로그인 ✅

| 티켓 | 접수(issued) | 처리 시작(started) | 처리 끝(finished) | claim 순서 |
|---|---|---|---|---|
| 3 | 20:42:37.472 | 20:42:38.249 | 20:42:38.372 | 2 |
| 4 | 20:42:37.484 | 20:42:38.249 | 20:42:38.486 | 3 |
| 5 | 20:42:37.495 | 20:42:39.509 | 20:42:39.637 | 4 |
| 6 | 20:42:37.507 | 20:42:39.509 | 20:42:39.766 | 5 |
| 7 | 20:42:37.527 | 20:42:40.779 | 20:42:40.892 | 6 |

- 5명 모두 로그인 200(`SUCCEEDED`/`OK`). claim 순서 1은 앞서 Chrome으로 로그인한 학생 `20260010`의 티켓이다.
- 티켓 번호 순으로 2건씩 claim됐다(3·4 → 5·6 → 7). 처리 구간이 겹친 최대 수 **2 = N**.
- 스크립트가 `PROCESSING`을 직접 관측한 최댓값은 0이었다(13회 관측). 한 건 처리가 0.1~0.25초라 `docker exec` 조회 간격보다 짧았다. 판정은 DB에 남은 처리 시작·끝 시각의 겹침으로 한다.
- Chrome: 로그인 버튼 → "로그인 대기 중" → 과목 검색 화면으로 이동.

### S2 — 정원 3 과목에 30명 동시 신청 ✅

| 확인 | 결과 |
|---|---|
| 결과 코드 | OK 3건, E07 27건 |
| `DEMO01` 정원·인원·ENROLLED 내역 | 3 · 3 · 3 |
| INV-2 검증 쿼리 | 0행 |
| Chrome 학생 `20260010`의 추가 신청 | "DEMO01 여석이 없습니다(대기 명단 없음)"(E07), 목록 여석 0 |

### S3 — 역할별 흐름 ✅ (모두 Chrome)

| 순서 | 누가 | 한 일 | 결과 |
|---|---|---|---|
| 준비 | 학생 `20260010` | `DEMO02`(정원 1) 신청 | "DEMO02 신청 완료", 1/1 |
| ⑤ | 학생 `20260010` | 학생 토큰으로 `POST /admin/enrollments`, `GET /admin/settings` | 둘 다 403 E02 `FORBIDDEN`, 배정 안 됨 |
| ① | 관리자 `A1001` | `20260009`를 `DEMO02`에 배정 | "배정 완료", 관리자 배정, **2 / 1 정원 초과** |
| ② | 학생 `20260008` | `DEMO02` 신청 | "DEMO02 여석이 없습니다"(E07). 목록은 정원 1·인원 2 |
| ③ | 학생 `20260010` | `DEMO02` 취소 | "DEMO02 취소 완료", 인원 2 → 1 |
| ④ | 교수 `P1001` | `DEMO01` 정원 3 → 2(현재 인원 3) | `PUT` 409, "정원은 현재 인원보다 작을 수 없습니다"(E11). 닫고 저장하지 않음 |

- INV-3: 인원 ≤ 정원 CHECK가 없어 관리자 배정은 정원을 넘고, 학생 신청만 E07로 막힌다(①②).
- E11은 `DEMO02`로는 만들 수 없다(현재 인원보다 작은 정원은 0이고 범위 밖 E09). 그래서 `DEMO01`로 확인했다.
- 화면 밖 증거(스키마의 CHECK 목록)는 리허설(X-07)에서 `\d courses`로 보여 준다.

### D-09 — `__Host-` 쿠키 (Vite 프록시 환경, 1차) ✅

| 쿠키 | 확인 방법 | 결과 |
|---|---|---|
| `__Host-crs_login` | 로그인 접수 → 티켓 조회 → 토큰 수령(이 쿠키가 있어야 성공) | Chrome이 저장·전송함. 5회 로그인 모두 성공 |
| `__Host-crs_refresh` | 로그인 후 새로고침 → `POST /api/v1/auth/refresh` 200 → `GET /courses` 200 | Chrome이 저장·전송함(HttpOnly라 화면 코드는 값을 읽을 수 없고, 쿠키 없이는 갱신이 200이 될 수 없다) |

- 남은 일: 김재섭님과 DevTools(Application → Cookies, Network → 요청 `Cookie` 헤더)로 속성(`Secure; HttpOnly; SameSite=Lax; Path=/`, Domain 없음)과 회전을 직접 기록한다. 루트 compose(`http://localhost`, Caddy) 환경에서도 같은 확인을 한다.
