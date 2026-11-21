#!/usr/bin/env bash
# =====================================================================
# S1(구현계획 9.2): N=2에서 학생 여러 명이 동시에 로그인한다.
#   티켓 번호 순으로 claim되고 PROCESSING은 N을 넘지 않는다. 완료 순서는 달라도 된다.
# 준비: reset-demo.sql로 초기화(N=2) → bootRun → 이 스크립트(Git Bash)
#   bash scripts/scenario-s1.sh            # 학생 20260011~20260015 (5명)
#   COUNT=4 bash scripts/scenario-s1.sh    # 브라우저에서 1명을 같이 로그인할 때
# 화면 밖 증거는 docker exec로 DB 컨테이너의 psql에서 바로 출력한다. 기본은 backend-practice/compose.yaml의 crs-db다.
#   루트 compose.yaml로 띄웠으면: BASE=http://localhost/api/v1 ORIGIN=http://localhost DB_CONTAINER=crs-db-1 bash scripts/scenario-s1.sh
# =====================================================================
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/smoke-lib.sh"
COUNT="${COUNT:-5}"; FIRST="${FIRST:-20260011}"
DB_CONTAINER="${DB_CONTAINER:-crs-db}"
PSQL=(docker exec "$DB_CONTAINER" psql -U crs -d crs -X -q -P pager=off)

echo "S1: 학생 $COUNT명 동시 로그인 ($BASE, N은 서버 설정값)"
require_server
"${PSQL[@]}" -t -A -c "SELECT 1" > /dev/null 2>&1 || { echo "DB 컨테이너 $DB_CONTAINER에 psql로 접속할 수 없다(DB_CONTAINER 확인)"; exit 1; }
"${PSQL[@]}" -c "SELECT concurrency_limit AS n FROM service_settings"
# 이번 실행에서 접수한 티켓만 증거로 고르기 위해 접수 전 마지막 번호를 기억한다
BEFORE="$("${PSQL[@]}" -t -A -c "SELECT COALESCE(max(ticket_id), 0) FROM tickets")"

# PROCESSING 수를 0.05초마다 관측해 최댓값을 기록한다(백그라운드)
OBS="$TMP/obs"; : > "$OBS"
( while [ -d "$TMP" ] && [ ! -f "$TMP/stop" ]; do
    "${PSQL[@]}" -t -A -c "SELECT count(*) FROM tickets WHERE status_cd='PROCESSING'" >> "$OBS" 2>/dev/null
    sleep 0.05
  done ) &
OBS_PID=$!
# 중간에 끝내도(Ctrl-C 등) 관측 루프가 남지 않게 한다
trap 'kill "$OBS_PID" 2>/dev/null; rm -rf "$TMP"' EXIT
trap 'exit 130' INT TERM

# 동시에 접수: 사용자마다 별도 파일로 헤더(소유 증명 쿠키)와 본문을 받는다
for i in $(seq 0 $((COUNT - 1))); do
  u=$((FIRST + i))
  printf '{"userId":"%s","password":"%s"}' "$u" "$PASSWORD" > "$TMP/req$u.json"
  curl -s -o "$TMP/body$u" -D "$TMP/head$u" -X POST "$BASE/auth/login" \
       -H "Origin: $ORIGIN" -H "X-CSRF-Protection: 1" -H "Content-Type: application/json" \
       --data-binary "@$TMP/req$u.json" &
done
wait $(jobs -p | grep -v "^$OBS_PID$") 2>/dev/null

# 모든 티켓이 종결될 때까지 기다린 뒤 토큰을 수령한다(로그인 완료)
ok=0
for i in $(seq 0 $((COUNT - 1))); do
  u=$((FIRST + i))
  id="$(sed -n 's/.*"ticketId":"\{0,1\}\([0-9]*\).*/\1/p' "$TMP/body$u")"
  proof="$(tr -d '\r' < "$TMP/head$u" | sed -n 's/^[Ss]et-[Cc]ookie: __Host-crs_login=\([^;]*\).*/\1/p')"
  if [ -n "$id" ] && wait_ticket "$id" -H "Cookie: __Host-crs_login=$proof"; then
    req POST "/auth/login-tickets/$id/tokens" "" -H "Cookie: __Host-crs_login=$proof"
    [ "$STATUS" = 200 ] && ok=$((ok + 1))
    echo "  $u 티켓 $id → 로그인 $STATUS"
  else
    echo "  $u 접수 실패 또는 시간 초과"
  fi
done
touch "$TMP/stop"; wait "$OBS_PID" 2>/dev/null
max="$(sort -n "$OBS" | tail -1)"

echo
echo "== 이번 실행의 로그인 티켓(번호 순, 번호 $BEFORE 초과). claim 순서 = processing_started_at 순서여야 한다"
"${PSQL[@]}" -c "SELECT ticket_id, status_cd, result_cd, ticket_issued_at::time(3) AS issued,
       processing_started_at::time(3) AS started, processing_finished_at::time(3) AS finished,
       rank() OVER (ORDER BY processing_started_at, ticket_id) AS claim_order
  FROM tickets WHERE request_type='LOGIN' AND ticket_id > $BEFORE ORDER BY ticket_id"
echo "== 처리 구간이 겹친 최대 수(기록 기준, N 이하여야 한다)"
"${PSQL[@]}" -c "WITH t AS (SELECT * FROM tickets WHERE request_type='LOGIN' AND ticket_id > $BEFORE)
  SELECT max(c) AS max_overlap FROM (
    SELECT (SELECT count(*) FROM t b WHERE b.processing_started_at <= a.processing_started_at
                                    AND b.processing_finished_at > a.processing_started_at) AS c FROM t a) y"
echo "로그인 성공 $ok/$COUNT, 관측한 PROCESSING 최댓값: ${max:-0} ($(wc -l < "$OBS")회 관측)"
