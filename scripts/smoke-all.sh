#!/usr/bin/env bash
# =====================================================================
# X-06 2·3단계 통합·회귀 점검 — 구현계획-backend.md 6.3절 X-06
# "20개 연산에 성공 1건과 대표 오류 1건씩"
#
#   [1]~[12]  1단계 연산 12개(smoke-p1.sh와 같음: 회귀 확인)
#   [13]~[20] 2·3단계 연산 8개(과목 상세·등록·폐강, 폐강 대상, 일괄 폐강, 토큰 갱신, 서비스 설정 조회·변경)
#
# 준비와 실행은 smoke-p1.sh와 같다(Windows는 Git Bash).
#   bash scripts/smoke-all.sh
#
# 데이터: [14]·[17]은 실행할 때마다 새 과목(RG·RH로 시작)을 등록하고 바로 폐강한다.
#         폐강 과목은 보존되므로 실행할수록 폐강된 테스트 과목이 쌓인다. 초기화는 DB 재생성으로 한다.
#         [18]은 STUDENT2의 세션을 재사용 탐지로 폐기한다. [20]은 N을 1 바꿨다가 원래 값으로 되돌린다.
# =====================================================================
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=smoke-p1.sh
source "$SCRIPT_DIR/smoke-p1.sh"   # smoke-lib.sh와 run_p1을 함께 불러온다

# 2·3단계 연산 8개
run_p23() {
  login "$PROFESSOR"; local P="$ACCESS"; local PA=(-H "Authorization: Bearer $P")
  login "$ADMIN";     local A="$ACCESS"; local AA=(-H "Authorization: Bearer $A")
  check "교수·관리자 다시 로그인" eval '[ -n "$P" ] && [ -n "$A" ]'

  # 실행마다 겹치지 않는 과목코드(영문자·숫자 10자 이하)
  local stamp; stamp="$(date +%s | tail -c 9)"
  local NEW="RG$stamp" BATCH="RH$stamp"
  local COURSE='"name":"회귀 점검 과목","category":"GENERAL","evaluationType":"PASS_FAIL","capacity":10,"minimumEnrollment":3,"classroom":"점검실 1"'

  # ===================== 13. GET /courses/{courseCode} =====================
  echo "[13] 과목 상세 GET /courses/{courseCode}"
  req GET "/courses/$COURSE_DETAIL" "" "${PA[@]}"
  check "200 과목 상세(교수 응답에는 enrolled 없음)" \
    eval '[ "$STATUS" = 200 ] && [ "$(json_get courseCode)" = "$COURSE_DETAIL" ] && ! contains "$BODY" "\"enrolled\""'
  req GET /courses/NOPE999 "" "${PA[@]}"
  check "없는 과목 → 404 E04" eval '[ "$STATUS" = 404 ] && [ "$(json_get code)" = E04 ]'

  # ===================== 14. POST /professor/courses =====================
  echo "[14] 과목 등록 POST /professor/courses"
  req POST /professor/courses "{\"courseCode\":\"$NEW\",$COURSE}" "${PA[@]}"
  check "201 + Location /api/v1/courses/$NEW, OPEN·인원 0" \
    eval '[ "$STATUS" = 201 ] && [ "$(header_get Location)" = "/api/v1/courses/$NEW" ] && [ "$(json_get status)" = OPEN ] && [ "$(json_get activeEnrollmentCount)" = 0 ]'
  req POST /professor/courses "{\"courseCode\":\"$COURSE_DETAIL\",$COURSE}" "${PA[@]}"
  check "이미 있는 과목코드 → 409 E10" eval '[ "$STATUS" = 409 ] && [ "$(json_get code)" = E10 ]'

  # ===================== 15. POST /professor/courses/{code}/closure =====================
  echo "[15] 교수 폐강 POST /professor/courses/{courseCode}/closure"
  req POST "/professor/courses/$NEW/closure" "" "${PA[@]}"
  check "200 과목 CLOSED·인원 0, closedEnrollmentCount" \
    eval '[ "$STATUS" = 200 ] && [ "$(json_get status)" = CLOSED ] && [ -n "$(json_get closedEnrollmentCount)" ]'
  req POST "/professor/courses/$NEW/closure" "" "${PA[@]}"
  check "같은 과목 다시 폐강 → 409 E14" eval '[ "$STATUS" = 409 ] && [ "$(json_get code)" = E14 ]'

  # ===================== 16. GET /admin/closure-candidates =====================
  echo "[16] 폐강 대상 GET /admin/closure-candidates"
  req GET "/admin/closure-candidates?page=1" "" "${AA[@]}"
  check "200 페이지(pageSize 20)" eval '[ "$STATUS" = 200 ] && [ "$(json_get pageSize)" = 20 ]'
  req GET "/admin/closure-candidates?page=0" "" "${AA[@]}"
  check "page=0 → 400 E09" eval '[ "$STATUS" = 400 ] && [ "$(json_get code)" = E09 ]'

  # ===================== 17. POST /admin/course-closures =====================
  echo "[17] 일괄 폐강 POST /admin/course-closures"
  req POST /professor/courses "{\"courseCode\":\"$BATCH\",$COURSE}" "${PA[@]}"   # 폐강 대상 하나 준비
  req POST /admin/course-closures "{\"courseCodes\":[\"$BATCH\",\"NOPE999\"]}" "${AA[@]}"
  check "200, 입력 순서대로 OK·E04" \
    eval '[ "$STATUS" = 200 ] && contains "$BODY" "\"courseCode\":\"$BATCH\",\"code\":\"OK\"" && contains "$BODY" "\"courseCode\":\"NOPE999\",\"code\":\"E04\""'
  req POST /admin/course-closures '{"courseCodes":[]}' "${AA[@]}"
  check "빈 배열 → 400 E09" eval '[ "$STATUS" = 400 ] && [ "$(json_get code)" = E09 ]'

  # ===================== 18. POST /auth/refresh =====================
  echo "[18] 토큰 갱신 POST /auth/refresh"
  login "$STUDENT2"; local OLD="$REFRESH"
  req POST /auth/refresh "" -H "Cookie: __Host-crs_refresh=$OLD"
  check "200 새 accessToken + user·session + 새 Refresh 쿠키(회전)" \
    eval '[ "$STATUS" = 200 ] && [ -n "$(json_get accessToken)" ] && contains "$BODY" "\"user\"" && [ -n "$(cookie_get __Host-crs_refresh)" ] && [ "$(cookie_get __Host-crs_refresh)" != "$OLD" ]'
  req POST /auth/refresh "" -H "Cookie: __Host-crs_refresh=$OLD"
  check "회전된 Refresh 재사용 → 401 REFRESH_TOKEN_REUSED" \
    eval '[ "$STATUS" = 401 ] && [ "$(json_get reason)" = REFRESH_TOKEN_REUSED ]'

  # ===================== 19. GET /admin/settings =====================
  echo "[19] 서비스 설정 조회 GET /admin/settings"
  req GET /admin/settings "" "${AA[@]}"
  local N; N="$(json_get concurrencyLimit)"
  check "200 concurrencyLimit·activeSlots·availableSlots·waitingCount" \
    eval '[ "$STATUS" = 200 ] && [ -n "$N" ] && contains "$BODY" "\"activeSlots\"" && contains "$BODY" "\"waitingCount\""'
  req GET /admin/settings "" "${PA[@]}"
  check "교수가 조회 → 403 E02" eval '[ "$STATUS" = 403 ] && [ "$(json_get code)" = E02 ]'

  # ===================== 20. PUT /admin/settings =====================
  echo "[20] 서비스 설정 변경 PUT /admin/settings"
  # 같은 값은 변경자를 기록하지 않으므로(계약) 1만큼 바꿨다가 원래 N으로 되돌린다
  local N2; if [ "${N:-100}" -ge 1000 ]; then N2=$(( ${N:-100} - 1 )); else N2=$(( ${N:-100} + 1 )); fi
  req PUT /admin/settings "{\"concurrencyLimit\":$N2}" "${AA[@]}"
  check "200, 새 N과 변경자 기록(lastChangedBy)" \
    eval '[ "$STATUS" = 200 ] && [ "$(json_get concurrencyLimit)" = "$N2" ] && [ "$(json_get lastChangedBy)" = "$ADMIN" ]'
  req PUT /admin/settings "{\"concurrencyLimit\":${N:-100}}" "${AA[@]}"   # 원래 값으로 복구
  req PUT /admin/settings '{"concurrencyLimit":0}' "${AA[@]}"
  check "범위 밖(0) → 400 E09" eval '[ "$STATUS" = 400 ] && [ "$(json_get code)" = E09 ]'
}

echo "X-06 회귀(20개 연산): $BASE (Origin $ORIGIN)"
require_server
run_p1
run_p23
finish
