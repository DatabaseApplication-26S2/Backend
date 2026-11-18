#!/usr/bin/env bash
# =====================================================================
# X-05 1단계 통합 점검(smoke) — 구현계획-backend.md 6.3절 X-05, 7절 "1단계 연산 12개의 성공과 대표 오류"
#
# 준비
#   1) docker compose up -d db redis
#   2) .\gradlew.bat bootRun              (기본 프로파일 local)
#   3) 테스트 계정·과목 데이터(local-test-data.sql)를 넣어 둔다
#      학생 20260003, 교수 prof01, 관리자 admin01 / 비밀번호 Test-password1!
#
# 실행(Windows는 Git Bash에서)
#   bash scripts/smoke-p1.sh
#   BASE=http://localhost:5173/api/v1 bash scripts/smoke-p1.sh   # Vite 프록시를 거쳐 확인할 때
#
# 결과: 모든 점검이 통과하면 exit 0, 하나라도 실패하면 실패 목록을 출력하고 exit 1
# 같은 데이터로 여러 번 실행할 수 있게, 만든 수강 내역은 마지막에 스스로 취소한다.
# =====================================================================
set -u

BASE="${BASE:-http://localhost:8080/api/v1}"
ORIGIN="${ORIGIN:-http://localhost:5173}"
PASSWORD="${PASSWORD:-Test-password1!}"
STUDENT="${STUDENT:-20260003}"
PROFESSOR="${PROFESSOR:-prof01}"
ADMIN="${ADMIN:-admin01}"
COURSE_ENROLL="${COURSE_ENROLL:-HIS1002}"   # 학생이 신청·취소할 과목(여석 있음, prof02 담당)
COURSE_ASSIGN="${COURSE_ASSIGN:-ELE015}"    # 관리자가 배정할 과목(학생이 마지막에 취소)
COURSE_CLOSED="${COURSE_CLOSED:-MAJ2001}"   # 폐강 과목
COURSE_OTHERS="${COURSE_OTHERS:-HIS1002}"   # prof01이 담당하지 않는 과목

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
PASS=0
FAILED=()

# ---------------- 도우미 ----------------

# req METHOD PATH [BODY] [추가 curl 옵션...] → $STATUS, $BODY, $HEADERS(파일)
req() {
  local method="$1" path="$2" body="${3:-}"
  shift 3 2>/dev/null || shift $#
  local args=(-s -o "$TMP/body" -D "$TMP/headers" -w '%{http_code}' -X "$method" "$BASE$path"
              -H "Origin: $ORIGIN" -H "X-CSRF-Protection: 1")
  if [ -n "$body" ]; then
    # 본문은 파일로 보낸다. 명령줄 인자로 넘기면 Windows에서 한글(UTF-8)이 깨질 수 있다
    printf '%s' "$body" > "$TMP/request.json"
    args+=(-H "Content-Type: application/json; charset=utf-8" --data-binary "@$TMP/request.json")
  fi
  STATUS="$(curl "${args[@]}" "$@")" || STATUS="000"
  BODY="$(cat "$TMP/body" 2>/dev/null)"
}

# json_get KEY : 응답 본문에서 "KEY":값 (문자열·숫자·불리언·null) 를 꺼낸다(jq 없이, 키가 여러 번 나오면 마지막 값)
json_get() {
  printf '%s' "$BODY" | tr -d '\n' | sed -n "s/.*\"$1\":\"\{0,1\}\([^\",}]*\)\"\{0,1\}.*/\1/p" | head -1
}

# Set-Cookie에서 쿠키 값 꺼내기(Secure 쿠키를 http로 다루려고 curl 쿠키 저장소 대신 직접 읽는다)
cookie_get() {
  tr -d '\r' < "$TMP/headers" | sed -n "s/^[Ss]et-[Cc]ookie: $1=\([^;]*\).*/\1/p" | head -1
}

header_get() {
  tr -d '\r' < "$TMP/headers" | sed -n "s/^$1: //Ip" | head -1
}

check() {  # check "이름" 조건식...
  local name="$1"; shift
  if "$@"; then
    PASS=$((PASS + 1)); printf '  \033[32mPASS\033[0m %s\n' "$name"
  else
    FAILED+=("$name (HTTP $STATUS) $(printf '%s' "$BODY" | head -c 200)")
    printf '  \033[31mFAIL\033[0m %s  → HTTP %s %s\n' "$name" "$STATUS" "$(printf '%s' "$BODY" | head -c 200)"
  fi
}
is() { [ "$1" = "$2" ]; }
contains() { case "$1" in *"$2"*) return 0 ;; *) return 1 ;; esac; }

# wait_ticket ID [추가 curl 옵션...] : 티켓이 종결될 때까지(최대 20초) 2초 권장 간격보다 짧게 0.5초마다 조회
wait_ticket() {
  local id="$1"; shift
  local i st
  for i in $(seq 1 40); do
    req GET "/tickets/$id" "" "$@"
    st="$(json_get status)"
    if [ "$st" = "SUCCEEDED" ] || [ "$st" = "REJECTED" ]; then return 0; fi
    sleep 0.5
  done
  return 1
}

# login USER → $ACCESS, $REFRESH (대기열 로그인 전 과정: 접수 → 티켓 대기 → 토큰 수령)
login() {
  ACCESS=""; REFRESH=""
  req POST /auth/login "{\"userId\":\"$1\",\"password\":\"$PASSWORD\"}"
  [ "$STATUS" = "202" ] || return 1
  local id proof
  id="$(json_get ticketId)"; proof="$(cookie_get __Host-crs_login)"
  [ -n "$id" ] && [ -n "$proof" ] || return 1
  wait_ticket "$id" -H "Cookie: __Host-crs_login=$proof" || return 1
  req POST "/auth/login-tickets/$id/tokens" "" -H "Cookie: __Host-crs_login=$proof"
  [ "$STATUS" = "200" ] || return 1
  ACCESS="$(json_get accessToken)"; REFRESH="$(cookie_get __Host-crs_refresh)"
  LOGIN_TICKET="$id"; LOGIN_PROOF="$proof"
  [ -n "$ACCESS" ]
}

echo "X-05 smoke: $BASE (Origin $ORIGIN)"
req GET /courses
if [ "$STATUS" = "000" ]; then
  echo "서버에 연결할 수 없습니다. bootRun이 켜져 있는지 확인하세요."; exit 1
fi

# ===================== 1. POST /auth/login =====================
echo "[1] 로그인 접수 POST /auth/login"
req POST /auth/login "{\"userId\":\"$STUDENT\",\"password\":\"$PASSWORD\"}"
check "202 접수증 + Location + Retry-After + __Host-crs_login 쿠키" \
  eval '[ "$STATUS" = 202 ] && [ -n "$(header_get Location)" ] && [ -n "$(header_get Retry-After)" ] && [ -n "$(cookie_get __Host-crs_login)" ]'
T_ID="$(json_get ticketId)"; T_PROOF="$(cookie_get __Host-crs_login)"
req POST /auth/login '{}'
check "본문 누락 → 400 E09" eval '[ "$STATUS" = 400 ] && [ "$(json_get code)" = E09 ]'

# ===================== 2. GET /tickets/{id} =====================
echo "[2] 티켓 조회 GET /tickets/{id}"
wait_ticket "$T_ID" -H "Cookie: __Host-crs_login=$T_PROOF"
check "소유 증명 쿠키로 조회 → 200, 결과 OK" eval '[ "$STATUS" = 200 ] && contains "$BODY" "\"code\":\"OK\""'
req GET "/tickets/$T_ID"
check "쿠키 없이 LOGIN 티켓 조회 → 403 E02" eval '[ "$STATUS" = 403 ] && [ "$(json_get code)" = E02 ]'

# ===================== 3. POST /auth/login-tickets/{id}/tokens =====================
echo "[3] 토큰 수령 POST /auth/login-tickets/{id}/tokens"
req POST "/auth/login-tickets/$T_ID/tokens" "" -H "Cookie: __Host-crs_login=$T_PROOF"
check "200 + accessToken + user·session + Refresh 쿠키" \
  eval '[ "$STATUS" = 200 ] && [ -n "$(json_get accessToken)" ] && [ "$(json_get role)" = STUDENT ] && contains "$BODY" "\"session\"" && [ -n "$(cookie_get __Host-crs_refresh)" ]'
S_ACCESS="$(json_get accessToken)"; S_REFRESH="$(cookie_get __Host-crs_refresh)"
req POST "/auth/login-tickets/$T_ID/tokens" "" -H "Cookie: __Host-crs_login=$T_PROOF"
check "두 번째 수령 → 403 LOGIN_EXCHANGE_CONSUMED" eval '[ "$STATUS" = 403 ] && [ "$(json_get reason)" = LOGIN_EXCHANGE_CONSUMED ]'
SA=(-H "Authorization: Bearer $S_ACCESS")

# 교수·관리자 로그인(뒤 점검에서 쓴다)
login "$PROFESSOR"; P_ACCESS="$ACCESS"; PA=(-H "Authorization: Bearer $P_ACCESS")
login "$ADMIN";     A_ACCESS="$ACCESS"; AA=(-H "Authorization: Bearer $A_ACCESS")
check "교수·관리자 로그인" eval '[ -n "$P_ACCESS" ] && [ -n "$A_ACCESS" ]'

# ===================== 4. POST /admission =====================
echo "[4] 화면 진입 POST /admission"
req POST /admission "" "${SA[@]}"
if [ "$STATUS" = "202" ]; then
  A_ID="$(json_get ticketId)"
  wait_ticket "$A_ID" "${SA[@]}"
  req POST /admission "" "${SA[@]}"
fi
check "입장 후 재요청 → 200 {admittedAt}" eval '[ "$STATUS" = 200 ] && [ -n "$(json_get admittedAt)" ]'
req POST /admission "" "${PA[@]}"
check "교수가 진입 → 403 E02" eval '[ "$STATUS" = 403 ] && [ "$(json_get code)" = E02 ]'

# ===================== 5. GET /courses =====================
echo "[5] 과목 검색 GET /courses"
req GET "/courses?page=1" "" "${SA[@]}"
check "200 페이지(pageSize 20) + Cache-Control: no-store + 학생 enrolled" \
  eval '[ "$STATUS" = 200 ] && [ "$(json_get pageSize)" = 20 ] && [ "$(header_get Cache-Control)" = no-store ] && contains "$BODY" "\"enrolled\""'
req GET "/courses?page=0" "" "${SA[@]}"
check "page=0 → 400 E09" eval '[ "$STATUS" = 400 ] && [ "$(json_get code)" = E09 ]'

# ===================== 6. POST /me/enrollments =====================
echo "[6] 수강 신청 POST /me/enrollments"
req POST /me/enrollments "{\"courseCode\":\"$COURSE_ENROLL\"}" "${SA[@]}"
check "202 접수증 + Location" eval '[ "$STATUS" = 202 ] && [ -n "$(header_get Location)" ]'
E_ID="$(json_get ticketId)"
[ -n "$E_ID" ] && wait_ticket "$E_ID" "${SA[@]}"
check "신청 티켓 결과 OK" eval 'contains "$BODY" "\"code\":\"OK\""'
req POST /me/enrollments '{"courseCode":"NOPE999"}' "${SA[@]}"
check "없는 과목 → 404 E04" eval '[ "$STATUS" = 404 ] && [ "$(json_get code)" = E04 ]'

# ===================== 7. POST /admin/enrollments =====================
echo "[7] 관리자 배정 POST /admin/enrollments"
req POST /admin/enrollments "{\"studentId\":\"$STUDENT\",\"courseCode\":\"$COURSE_ASSIGN\"}" "${AA[@]}"
check "201 enrollmentType ADMIN" eval '[ "$STATUS" = 201 ] && [ "$(json_get enrollmentType)" = ADMIN ]'
req POST /admin/enrollments "{\"studentId\":\"$STUDENT\",\"courseCode\":\"$COURSE_CLOSED\"}" "${AA[@]}"
check "폐강 과목 → 409 E05" eval '[ "$STATUS" = 409 ] && [ "$(json_get code)" = E05 ]'

# ===================== 8. GET /me/enrollments =====================
echo "[8] 내 신청 내역 GET /me/enrollments"
req GET /me/enrollments "" "${SA[@]}"
check "200, 신청 과목과 배정 과목이 모두 있음" \
  eval '[ "$STATUS" = 200 ] && contains "$BODY" "$COURSE_ENROLL" && contains "$BODY" "$COURSE_ASSIGN"'
req GET /me/enrollments "" "${PA[@]}"
check "교수가 조회 → 403 E02" eval '[ "$STATUS" = 403 ] && [ "$(json_get code)" = E02 ]'

# ===================== 9. DELETE /me/enrollments/{code} =====================
echo "[9] 수강 취소 DELETE /me/enrollments/{courseCode}"
for code in "$COURSE_ENROLL" "$COURSE_ASSIGN"; do     # 관리자 배정 행도 학생이 취소할 수 있다
  req DELETE "/me/enrollments/$code" "" "${SA[@]}"
  C_STATUS="$STATUS"; C_ID="$(json_get ticketId)"
  [ -n "$C_ID" ] && wait_ticket "$C_ID" "${SA[@]}"
  check "$code 취소 → 202, 티켓 결과 OK" eval '[ "$C_STATUS" = 202 ] && contains "$BODY" "\"code\":\"OK\""'
done
req DELETE /me/enrollments/NOPE999 "" "${SA[@]}"
check "없는 과목 취소 → 404 E04" eval '[ "$STATUS" = 404 ] && [ "$(json_get code)" = E04 ]'

# ===================== 10. GET /professor/courses =====================
echo "[10] 담당 과목 GET /professor/courses"
req GET /professor/courses "" "${PA[@]}"
check "200 {items}, 폐강 과목 포함" eval '[ "$STATUS" = 200 ] && contains "$BODY" "$COURSE_CLOSED"'
req GET /professor/courses "" "${SA[@]}"
check "학생이 조회 → 403 E02" eval '[ "$STATUS" = 403 ] && [ "$(json_get code)" = E02 ]'

# ===================== 11. PUT /professor/courses/{code} =====================
echo "[11] 과목 수정 PUT /professor/courses/{courseCode}"
# 같은 값으로 덮어써서 여러 번 실행해도 데이터가 바뀌지 않게 한다(CSE4020 컴파일러)
UPD='{"name":"컴파일러","category":"MAJOR","evaluationType":"ABSOLUTE","capacity":30,"minimumEnrollment":6,"classroom":"공학관 402"}'
req PUT /professor/courses/CSE4020 "$UPD" "${PA[@]}"
check "200 수정된 과목" eval '[ "$STATUS" = 200 ] && [ "$(json_get courseCode)" = CSE4020 ]'
req PUT "/professor/courses/$COURSE_OTHERS" "$UPD" "${PA[@]}"
check "남의 과목 → 403 E02 FORBIDDEN" eval '[ "$STATUS" = 403 ] && [ "$(json_get reason)" = FORBIDDEN ]'

# ===================== 12. POST /auth/logout =====================
echo "[12] 로그아웃 POST /auth/logout"
req POST /auth/logout "" -H "Cookie: __Host-crs_refresh=$S_REFRESH"
check "204" eval '[ "$STATUS" = 204 ]'
req GET /me/enrollments "" "${SA[@]}"
check "로그아웃한 Access로 호출 → 401" eval '[ "$STATUS" = 401 ]'

# ===================== 결과 =====================
echo
TOTAL=$((PASS + ${#FAILED[@]}))
if [ "${#FAILED[@]}" -eq 0 ]; then
  printf '\033[32m모든 점검 통과: %d/%d\033[0m\n' "$PASS" "$TOTAL"
  exit 0
fi
printf '\033[31m실패 %d건 / 전체 %d건\033[0m\n' "${#FAILED[@]}" "$TOTAL"
for f in "${FAILED[@]}"; do echo " - $f"; done
exit 1
