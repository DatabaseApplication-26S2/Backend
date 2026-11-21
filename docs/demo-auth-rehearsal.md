# 김재섭 시연 준비 — 2026-11-20

별도 `demo` 프로파일 DB(PostgreSQL 17)와 Redis에서 실행한다. V900은 학생 20260001~20260060,
교수 P1001~P1003, 관리자 A1001을 생성한다. 시연 비밀번호는 `Demo-password1!`이다.
DEMO01은 정원 3, 초기 신청 0명이다. 아래 명령은 PowerShell 7 기준이다.
실제 프런트엔드 및 11/26 팀 리허설 완료를 의미하지 않는다.

## S1: N=2에서 동시 로그인 5건

```powershell
./scripts/demo-concurrent.ps1 -Scenario Login -Base http://127.0.0.1:18080/api/v1
```

스크립트는 PowerShell 7 이상(`pwsh`)이 필요하다. 설치: Windows `winget install Microsoft.PowerShell`, macOS `brew install powershell`.

관리자로 N을 2로 설정하고, 별도 병렬 작업 5개가 접수→조회→토큰 수령을 진행한다.
5건 모두 OK여야 한다. 종료 시 원래 N을 복원한다. 토큰과 쿠키는 출력하지 않는다.
동시 요청은 실행 시점까지 완전히 일치한다는 뜻이 아니라 5개 작업이 겹쳐 실행된다는 뜻이다.
슬롯 상한의 지속 준수는 `ServiceSettingsIntegrationTest` 등 DB 동시성 테스트로 별도 검증한다.

## S2: 정원 3에 동시 신청 30건

```powershell
./scripts/demo-concurrent.ps1 -Scenario Enrollment -Base http://127.0.0.1:18080/api/v1
```

30개 학생 세션의 로그인과 입장을 모두 마친 뒤 병렬로 DEMO01 신청을 접수한다.
성공 3건 / E07 27건이 아니면 실패한다. 이미 신청자가 있거나 폐강된 DEMO01이면 시작을 거절한다.
이 스크립트는 DB를 초기화하지 않는다. 재실행할 때는 앱을 중지하고 전용 DB를 새로 준비하거나
검토한 `scripts/reset-demo.sql`을 사용한다. 폐강 과목 상태는 해당 리셋으로 복구되지 않는다.

## 결과 SQL 및 S4 폐강 전후

```powershell
Get-Content scripts/demo-verify.sql -Raw | docker exec -i <시연DB컨테이너> psql -U crs -d crs -v course_code=DEMO01
```

신청 결과 집계, INV-2 불일치(0행), courses/enrollments/tickets 세 테이블을 같은 스냅샷으로 조회한다.
폐강 전 출력을 보관하고, S4 폐강 요청을 마친 뒤 같은 SQL로 폐강 후 출력을 보관한다.
과목 CLOSED·active_cnt=0, 기존 내역 CLOSED 보존, 대기 신청 E05를 함께 확인한다.
예전 실행 티켓도 집계하므로 비교 시 새 DB 또는 출력된 ticketId 범위를 기준으로 한다.

## S5: 과목 잠금 중 MVCC 조회

1. 자동 종료하는 `psql -c` 대신 **대화형 psql 세션 A**를 연다.

   ```text
   docker exec -it <시연DB컨테이너> psql -U crs -d crs
   BEGIN;
   SELECT course_code FROM courses WHERE course_code='DEMO01' FOR NO KEY UPDATE;
   ```

2. COMMIT하지 않고 세션 A를 그대로 둔다. 별도 브라우저/터미널 B에서 입장한 학생이나
   교수·관리자의 Access 토큰으로 `GET /api/v1/courses`를 호출한다. 목록이 즉시 반환되어야 한다.
   토큰을 카톡이나 결과 파일에 복사하지 않는다. 과목 수정/신청은 잠금 때문에 기다릴 수 있다.
3. 별도 관리자 세션에서 N을 2→1로 변경한다. 설정 조회로 N과 슬롯 수를 확인한다.
   대기 작업이 있으면 기존 작업 종료 뒤 새 N에 따라 추가 claim이 이뤄지는지 확인한다.
4. 세션 A에서 `ROLLBACK;`으로 잠금을 해제하고 N을 원래 값으로 돌린다.

워커 중단·재투입은 담당자의 BE-17 시나리오에 맞춰 추가한다. LOGIN은 프로세스 재시작으로
메모리 자격 증명이 사라지면 E01이 정상 결과다. `AuthIntegrationTest`가 이 조건과
구세대 워커의 뒤늦은 실행이 새 세대의 자격 증명을 소모하지 않는 조건을 검증한다.

## 11/20 실행 증거

- 별도 PostgreSQL 17 / Redis 7 / Java 21 서버에서 S1: 5 OK.
- S2: 티켓 68~97, 3 OK / 27 E07. N=2. 검증 SQL 실행: INV-2 불일치 0행.
- S4/S5 전체 브라우저 시연과 11/26 리허설은 팀에서 진행한다.
