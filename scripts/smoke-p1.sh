#!/usr/bin/env bash
# =====================================================================
# X-05 1단계 통합 점검(smoke) — 구현계획-backend.md 6.3절 X-05, 7절 "1단계 연산 12개의 성공과 대표 오류"
#
# 준비
#   1) docker compose up -d db redis
#   2) .\gradlew.bat bootRun              (기본 프로파일 local: V900 시연 시드가 들어간다)
#   기본값은 V900 시드다(학생 20260003, 교수 P1001, 관리자 A1001 / 비밀번호 Demo-password1!).
#   다른 SQL을 미리 넣을 필요가 없다. 폐강 과목(SMKCLOSED)은 처음 실행할 때 스크립트가 API로 만든다.
#
# 실행(Windows는 Git Bash)
#   bash scripts/smoke-p1.sh
#   BASE=http://localhost:5173/api/v1 bash scripts/smoke-p1.sh   # Vite 프록시를 거쳐 확인할 때
#
# 결과: 모든 점검이 통과하면 exit 0, 하나라도 실패하면 실패 목록을 출력하고 exit 1
# 같은 데이터로 여러 번 실행할 수 있게, 만든 수강 내역은 마지막에 스스로 취소한다.
# 2·3단계까지 포함한 20개 연산 점검은 smoke-all.sh(X-06)를 쓴다.
# =====================================================================
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=smoke-lib.sh
source "$SCRIPT_DIR/smoke-lib.sh"

# 1단계 연산 12개
run_p1() {
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
  ensure_closed_course "$P_ACCESS"   # [7]·[10]에서 쓰는 폐강 과목 준비

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
  # 현재 값 그대로 다시 저장해서 여러 번 실행해도 데이터가 바뀌지 않게 한다
  course_update_body "$COURSE_OWN" "$P_ACCESS"
  req PUT "/professor/courses/$COURSE_OWN" "$UPD" "${PA[@]}"
  check "200 수정된 과목" eval '[ "$STATUS" = 200 ] && [ "$(json_get courseCode)" = "$COURSE_OWN" ]'
  req PUT "/professor/courses/$COURSE_OTHERS" "$UPD" "${PA[@]}"
  check "남의 과목 → 403 E02 FORBIDDEN" eval '[ "$STATUS" = 403 ] && [ "$(json_get reason)" = FORBIDDEN ]'

  # ===================== 12. POST /auth/logout =====================
  echo "[12] 로그아웃 POST /auth/logout"
  req POST /auth/logout "" -H "Cookie: __Host-crs_refresh=$S_REFRESH"
  check "204" eval '[ "$STATUS" = 204 ]'
  req GET /me/enrollments "" "${SA[@]}"
  check "로그아웃한 Access로 호출 → 401" eval '[ "$STATUS" = 401 ]'
}

if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  echo "X-05 smoke(1단계 12개 연산): $BASE (Origin $ORIGIN)"
  require_server
  run_p1
  finish
fi
