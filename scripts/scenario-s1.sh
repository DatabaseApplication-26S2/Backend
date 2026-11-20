#!/usr/bin/env bash
# =====================================================================
# S1(구현계획 9.2): N=2에서 학생 여러 명이 동시에 로그인한다.
#   티켓 번호 순으로 claim되고 PROCESSING은 N을 넘지 않는다. 완료 순서는 달라도 된다.
# 준비: reset-demo.sql로 초기화(N=2) → bootRun → 이 스크립트(Git Bash)
#   bash scripts/scenario-s1.sh            # 학생 20260011~20260015 (5명)
#   COUNT=4 bash scripts/scenario-s1.sh    # 브라우저에서 1명을 같이 로그인할 때
# 화면 밖 증거는 docker exec crs-db psql로 바로 출력한다.
# =====================================================================
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/smoke-lib.sh"
COUNT="${COUNT:-5}"; FIRST="${FIRST:-20260011}"
PSQL=(docker exec crs-db psql -U crs -d crs -X -q -P pager=off)

echo "S1: 학생 $COUNT명 동시 로그인 ($BASE, N은 서버 설정값)"
require_server
"${PSQL[@]}" -c "SELECT concurrency_limit AS n FROM service_settings"

# PROCESSING 수를 0.05초마다 관측해 최댓값을 기록한다(백그라운드)
OBS="$TMP/obs"; : > "$OBS"
( while [ ! -f "$TMP/stop" ]; do
    "${PSQL[@]}" -t -A -c "SELECT count(*) FROM tickets WHERE status_cd='PROCESSING'" >> "$OBS" 2>/dev/null
    sleep 0.05
  done ) &
OBS_PID=$!

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
echo "== 로그인 티켓(번호 순). claim 순서 = processing_started_at 순서여야 한다"
"${PSQL[@]}" -c "SELECT ticket_id, status_cd, result_cd, ticket_issued_at::time(3) AS issued,
       processing_started_at::time(3) AS started, processing_finished_at::time(3) AS finished,
       rank() OVER (ORDER BY processing_started_at, ticket_id) AS claim_order
  FROM tickets WHERE request_type='LOGIN' ORDER BY ticket_id DESC LIMIT $COUNT"
echo "== 처리 구간이 겹친 최대 수(기록 기준, N 이하여야 한다)"
"${PSQL[@]}" -c "WITH t AS (SELECT * FROM (SELECT * FROM tickets WHERE request_type='LOGIN' ORDER BY ticket_id DESC LIMIT $COUNT) x)
  SELECT max(c) AS max_overlap FROM (
    SELECT (SELECT count(*) FROM t b WHERE b.processing_started_at <= a.processing_started_at
                                    AND b.processing_finished_at > a.processing_started_at) AS c FROM t a) y"
echo "로그인 성공 $ok/$COUNT, 관측한 PROCESSING 최댓값: ${max:-0} ($(wc -l < "$OBS")회 관측)"
