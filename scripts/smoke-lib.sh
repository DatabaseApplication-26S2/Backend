#!/usr/bin/env bash
# =====================================================================
# smoke 공통 도우미(X-05·X-06). 직접 실행하지 않고 smoke-p1.sh·smoke-all.sh가 source한다.
# 기본값은 V900 시연 시드(local·demo 프로파일, db/seed/V900__seed_demo.sql)다. 환경 변수 없이 실행한다.
# 설정은 환경 변수로 바꿀 수 있다: BASE, ORIGIN, PASSWORD, STUDENT, STUDENT2, PROFESSOR, ADMIN,
#   COURSE_ENROLL, COURSE_ASSIGN, COURSE_CLOSED, COURSE_OTHERS, COURSE_OWN, COURSE_DETAIL
# =====================================================================
set -u

BASE="${BASE:-http://localhost:8080/api/v1}"
ORIGIN="${ORIGIN:-http://localhost:5173}"
PASSWORD="${PASSWORD:-Demo-password1!}"      # V900 시드 계정 공통 비밀번호(시연 전용)
STUDENT="${STUDENT:-20260003}"
STUDENT2="${STUDENT2:-20260002}"          # 갱신(재사용 탐지) 점검 전용. 이 학생의 세션은 점검 중 폐기된다
PROFESSOR="${PROFESSOR:-P1001}"
ADMIN="${ADMIN:-A1001}"
COURSE_ENROLL="${COURSE_ENROLL:-DEMO05}"    # 학생이 신청·취소할 과목(정원 30, P1002 담당)
COURSE_ASSIGN="${COURSE_ASSIGN:-DEMO03}"    # 관리자가 배정할 과목(정원 30, 학생이 마지막에 취소)
COURSE_CLOSED="${COURSE_CLOSED:-SMKCLOSED}" # 폐강 과목. 없으면 PROFESSOR가 등록·폐강해 준비한다(ensure_closed_course)
COURSE_OTHERS="${COURSE_OTHERS:-DEMO05}"    # PROFESSOR가 담당하지 않는 과목
COURSE_OWN="${COURSE_OWN:-DEMO04}"          # PROFESSOR 담당 과목. 현재 값 그대로 다시 저장해 수정을 확인한다
COURSE_DETAIL="${COURSE_DETAIL:-DEMO01}"    # X-06: 상세 조회·중복 등록에 쓸 기존 과목

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
    # A failed token-response assertion can still contain a valid Access token.
    # Never copy response bodies or cookies into terminal/CI logs.
    FAILED+=("$name (HTTP $STATUS)")
    printf '  \033[31mFAIL\033[0m %s  → HTTP %s\n' "$name" "$STATUS"
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


# ensure_closed_course 교수_토큰 : COURSE_CLOSED를 PROFESSOR 담당 폐강 과목으로 준비한다(여러 번 실행해도 같은 결과)
#   없으면 등록(최소 수강인원 1, 인원 0) 뒤 폐강, OPEN이면 폐강, 이미 CLOSED면 그대로 둔다. 시드에 폐강 과목이 없어서 필요하다
ensure_closed_course() {
  local auth=(-H "Authorization: Bearer $1")
  req GET "/courses/$COURSE_CLOSED" "" "${auth[@]}"
  if [ "$STATUS" = 404 ]; then
    req POST /professor/courses "{\"courseCode\":\"$COURSE_CLOSED\",\"name\":\"점검용 폐강 과목\",\"category\":\"MAJOR\",\"evaluationType\":\"ABSOLUTE\",\"capacity\":30,\"minimumEnrollment\":1,\"classroom\":\"점검실\"}" "${auth[@]}"
    req POST "/professor/courses/$COURSE_CLOSED/closure" "" "${auth[@]}"
  elif [ "$STATUS" = 200 ] && [ "$(json_get status)" = OPEN ]; then
    req POST "/professor/courses/$COURSE_CLOSED/closure" "" "${auth[@]}"
  fi
}

# course_update_body 과목코드 토큰 → $UPD : 과목의 현재 값으로 만든 수정 본문(같은 값 저장이라 데이터가 바뀌지 않는다)
course_update_body() {
  req GET "/courses/$1" "" -H "Authorization: Bearer $2"
  UPD="{\"name\":\"$(json_get name)\",\"category\":\"$(json_get category)\",\"evaluationType\":\"$(json_get evaluationType)\",\"capacity\":$(json_get capacity),\"minimumEnrollment\":$(json_get minimumEnrollment),\"classroom\":\"$(json_get classroom)\"}"
}

# 서버 연결 확인
require_server() {
  req GET /courses
  if [ "$STATUS" = "000" ]; then
    echo "서버에 연결할 수 없습니다. bootRun이 켜져 있는지 확인하세요."; exit 1
  fi
}

# 결과 요약 후 종료(모두 통과 0, 하나라도 실패 1)
finish() {
  echo
  local total=$((PASS + ${#FAILED[@]}))
  if [ "${#FAILED[@]}" -eq 0 ]; then
    printf '\033[32m모든 점검 통과: %d/%d\033[0m\n' "$PASS" "$total"
    exit 0
  fi
  printf '\033[31m실패 %d건 / 전체 %d건\033[0m\n' "${#FAILED[@]}" "$total"
  local f
  for f in "${FAILED[@]}"; do echo " - $f"; done
  exit 1
}
