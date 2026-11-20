# X-06 2·3단계 통합·회귀 점검표

| 항목 | 내용 |
|---|---|
| 작업 | X-06 2·3단계 통합·회귀(구현계획-backend.md 6.3절), 담당 팀원3 |
| 마일스톤 | M4(11/20) |
| 완료 조건 | 20개 연산에 성공 1건과 대표 오류 1건씩, BE-16 통과 |
| 착수 선행 | X-05(1단계 통합) |
| 완료 선행 | BE-11·BE-16(팀원2), BE-14·BE-17(팀원1), FE-09~FE-12(팀장) |
| 자동 점검 | Git Bash에서 `bash scripts/smoke-all.sh` (1단계 12개 + 2·3단계 8개) |

## 0. 실행 방법

서버를 켠 뒤 Git Bash(또는 PowerShell에서 `& "C:\Program Files\Git\bin\bash.exe"`)로 실행한다.

```bash
bash scripts/smoke-all.sh
```

기본값은 V900 시연 시드다(local·demo 프로파일이 자동 적용). 환경 변수나 보조 SQL 없이 실행한다.

| 변수 | 기본값 | 쓰임 |
|---|---|---|
| `STUDENT` · `STUDENT2` | `20260003` · `20260002` | 학생. `STUDENT2`는 [18] 재사용 탐지로 세션이 폐기된다 |
| `PROFESSOR` · `ADMIN` | `P1001` · `A1001` | 교수·관리자(비밀번호 `Demo-password1!`) |
| `COURSE_ENROLL` · `COURSE_OTHERS` | `DEMO05` | 학생 신청·취소, P1001이 담당하지 않는 과목 |
| `COURSE_ASSIGN` | `DEMO03` | 관리자 배정 |
| `COURSE_OWN` | `DEMO04` | P1001 담당 과목. 현재 값 그대로 다시 저장해 수정을 확인한다 |
| `COURSE_CLOSED` | `SMKCLOSED` | 폐강 과목. 없으면 스크립트가 P1001로 등록·폐강해 만든다(처음 한 번) |
| `COURSE_DETAIL` | `DEMO01` | [13] 상세 조회와 [14] 중복 등록(E10) |

다른 서버나 데이터로 실행할 때만 환경 변수로 바꾼다. 예: `BASE=http://127.0.0.1:18080/api/v1 bash scripts/smoke-all.sh`
- 실패 출력에는 연산 이름과 HTTP 상태만 남는다. 응답 본문과 쿠키는 출력하지 않는다(토큰 노출 방지).

### 통합본(main `ed4a316`, 11/19) 기준 예상

| 연산 | 예상 | 이유 |
|---|---|---|
| [1]~[17] | 통과 | 1단계 + BE-12·13 |
| [18] | 통과 | BE-11의 재사용 탐지(`REFRESH_TOKEN_REUSED`)가 통합본에 들어옴 |
| [19]·[20] | 통과 | BE-14 서비스 설정 구현(`feature/sub-course-admin-m4`, 11/20). [20]은 N을 1 바꿨다가 되돌린다 |
| [19] 교수 403 | 통과 | 보안 필터가 먼저 막음 |

진행 방식: 끝난 연산부터 표를 채우고, 선행 패키지가 끝나는 대로 나머지를 확인한다(9.4절).
결과 표기: ✅ 통과 · ❌ 실패 · ⏳ 선행 패키지 미완료

## 1. 연산별 점검(smoke-all.sh)

| # | 연산 | 패키지(담당) | 성공 확인 | 대표 오류 확인 | 결과 |
|---|---|---|---|---|---|
| 1 | `POST /auth/login` | BE-05(팀원2) | 202, `Location`, `Retry-After`, 소유 증명 쿠키 | 본문 누락 → 400 E09 | ✅ |
| 2 | `GET /tickets/{id}` | BE-03(팀원1) | 소유 증명 쿠키로 조회 → 결과 OK | 쿠키 없이 LOGIN 티켓 → 403 E02 | ✅ |
| 3 | `POST /auth/login-tickets/{id}/tokens` | BE-05(팀원2) | 200, `user`·`session`, Refresh 쿠키 | 두 번째 수령 → 403 `LOGIN_EXCHANGE_CONSUMED` | ✅ |
| 4 | `POST /admission` | BE-06(팀원2) | 202 → 재요청 200 `{admittedAt}` | 교수 → 403 E02 | ✅ |
| 5 | `GET /courses` | BE-07(팀원3) | 200, `pageSize` 20, `no-store`, 학생 `enrolled` | `page=0` → 400 E09 | ✅ |
| 6 | `POST /me/enrollments` | BE-08(팀원1) | 202 + `Location`, 티켓 결과 OK | 없는 과목 → 404 E04 | ✅ |
| 7 | `POST /admin/enrollments` | BE-09(팀원3) | 201, `enrollmentType` ADMIN | 폐강 과목 → 409 E05 | ✅ |
| 8 | `GET /me/enrollments` | BE-07(팀원3) | 200, 신청·배정 모두 포함 | 교수 → 403 E02 | ✅ |
| 9 | `DELETE /me/enrollments/{code}` | BE-08(팀원1) | 202, 티켓 결과 OK | 없는 과목 → 404 E04 | ✅ |
| 10 | `GET /professor/courses` | BE-07(팀원3) | 200, 폐강 과목 포함 | 학생 → 403 E02 | ✅ |
| 11 | `PUT /professor/courses/{code}` | BE-09(팀원3) | 200 수정된 과목 | 남의 과목 → 403 `FORBIDDEN` | ✅ |
| 12 | `POST /auth/logout` | BE-06·BE-11(팀원2) | 204 | 로그아웃한 Access → 401 | ✅ |
| 13 | `GET /courses/{code}` | BE-12(팀원3) | 200 과목 상세 | 없는 과목 → 404 E04 | ✅ |
| 14 | `POST /professor/courses` | BE-12(팀원3) | 201 + `Location`, OPEN·인원 0 | 이미 있는 코드 → 409 E10 | ✅ |
| 15 | `POST /professor/courses/{code}/closure` | BE-13(팀원3) | 200 CLOSED·인원 0 | 다시 폐강 → 409 E14 | ✅ |
| 16 | `GET /admin/closure-candidates` | BE-13(팀원3) | 200 페이지 | `page=0` → 400 E09 | ✅ |
| 17 | `POST /admin/course-closures` | BE-13(팀원3) | 200, 입력 순서 OK·E04 | 빈 배열 → 400 E09 | ✅ |
| 18 | `POST /auth/refresh` | BE-11(팀원2) | 200 새 토큰 + 새 Refresh(회전) | 재사용 → 401 `REFRESH_TOKEN_REUSED` | ✅ |
| 19 | `GET /admin/settings` | BE-14(팀원3) | 200 N·슬롯·대기 수 | 교수 → 403 E02 | ✅ |
| 20 | `PUT /admin/settings` | BE-14(팀원3) | 200, 새 N·변경자 기록 | 범위 밖(0) → 400 E09 | ✅ |

**실행 기록:** 2026-11-20 20:28, `feature/sub-course-admin-m5`(main `3237fd9` + BE-14), local 프로파일·V900 시드(새 DB), 환경 변수 없이 기본값 — **`smoke-all.sh` 45/45 통과(exit 0)**

## 2. 계약 적합성(BE-16, 팀원2)

| 항목 | 결과 | 비고 |
|---|---|---|
| 20개 연산 응답이 `openapi.yaml` 스키마에 맞음 | | |
| 검증기가 음성 사례(타입·열거·범위·추가 필드·조건부 스키마 위반)를 거절함 | | |
| DB 덤프와 로그에서 원문 비밀 0건 | | |

## 3. 프런트엔드 연동(FE-09~FE-12, 팀장)

| 화면 | 연산 | 확인한 사람 | 결과 | 비고 |
|---|---|---|---|---|
| 새로고침 후 로그인 유지(토큰 갱신) | 18 | | | |
| 과목 상세 | 13 | | | |
| 교수: 과목 등록·폐강 | 14, 15 | | | |
| 관리자: 폐강 대상·일괄 폐강 | 16, 17 | | | |
| 관리자: 서비스 설정 | 19, 20 | | | |

## 4. 발견한 문제

| 날짜 | 연산·화면 | 내용 | 담당 | 상태 |
|---|---|---|---|---|
| | | | | |
