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
| 테스트 데이터 | 계정 학생 `20260001`~`20260003`, 교수 `prof01`·`prof02`, 관리자 `admin01`(비밀번호 공통), 과목 23개 |
| 자동 점검 | Git Bash에서 `bash scripts/smoke-p1.sh` |

## 2. 연산별 점검(smoke-p1.sh)

결과 열은 실행할 때마다 채운다. ? 통과 · ? 실패 · ?? 계약과 다름(담당자 확인 필요)

**실행 기록:** 2026-11-18 11:33, `feature/sub-course-admin`, local 프로파일 ? **27/27 통과(exit 0)**

| # | 연산 | 담당 | 성공 확인 | 대표 오류 확인 | 결과 |
|---|---|---|---|---|---|
| 1 | `POST /auth/login` | 김재섭님 | 202, `Location`, `Retry-After`, `__Host-crs_login` 쿠키 | 본문 누락 → 400 E09 | ? |
| 2 | `GET /tickets/{id}` | 기태님 | 소유 증명 쿠키로 조회 → 200, 결과 OK | 쿠키 없이 LOGIN 티켓 → 403 E02 | ? |
| 3 | `POST /auth/login-tickets/{id}/tokens` | 김재섭님 | 200, `accessToken`·`user`·`session`, Refresh 쿠키 | 두 번째 수령 → 403 `LOGIN_EXCHANGE_CONSUMED` | ? |
| 4 | `POST /admission` | 김재섭님 | 202 → 입장 후 재요청 200 `{admittedAt}` | 교수 → 403 E02 | ? |
| 5 | `GET /courses` | 백진현 | 200, `pageSize` 20, `no-store`, 학생 `enrolled` | `page=0` → 400 E09 | ? |
| 6 | `POST /me/enrollments` | 기태님 | 202 + `Location`, 티켓 결과 OK | 없는 과목 → 404 E04 | ? |
| 7 | `POST /admin/enrollments` | 백진현 | 201, `enrollmentType` ADMIN | 폐강 과목 → 409 E05 | ? |
| 8 | `GET /me/enrollments` | 백진현 | 200, 학생 신청·관리자 배정 모두 포함 | 교수 → 403 E02 | ? |
| 9 | `DELETE /me/enrollments/{code}` | 기태님 | 202, 티켓 결과 OK(관리자 배정 행도 취소) | 없는 과목 → 404 E04 | ? |
| 10 | `GET /professor/courses` | 백진현 | 200, 폐강 과목 포함 | 학생 → 403 E02 | ? |
| 11 | `PUT /professor/courses/{code}` | 백진현 | 200 수정된 과목 | 남의 과목 → 403 `FORBIDDEN` | ? |
| 12 | `POST /auth/logout` | 김재섭님 | 204 | 로그아웃한 Access → 401 | ? |

## 3. 브라우저 시나리오

S1~S3의 정의는 구현계획.md#verification을 따른다. 아래 표에 시나리오 이름과 단계를 옮겨 적고 결과를 기록한다.

| 시나리오 | 내용(구현계획.md#verification에서 옮겨 적기) | 확인한 사람 | 결과 | 비고 |
|---|---|---|---|---|
| S1 | | | | |
| S2 | | | | |
| S3 | | | | |

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
