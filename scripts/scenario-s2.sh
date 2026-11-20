#!/usr/bin/env bash
# =====================================================================
# S2(구현계획 9.2): 정원 3 과목에 학생 30명이 동시에 신청한다.
#   OK 3건과 E07 27건, INV-2 검증 쿼리 0행.
# 준비: reset-demo.sql로 초기화 → bootRun → 이 스크립트(Git Bash)
#   bash scripts/scenario-s2.sh                 # 학생 20260021~20260050, 과목 DEMO01(정원 3)
#   COUNT=29 bash scripts/scenario-s2.sh        # 브라우저에서 1명을 같이 신청할 때
# 1) 학생마다 로그인·입장을 미리 끝내고 2) 신청 요청만 한꺼번에 보낸다.
# =====================================================================
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/smoke-lib.sh"
COUNT="${COUNT:-30}"; FIRST="${FIRST:-20260021}"; COURSE="${COURSE:-DEMO01}"
PSQL=(docker exec crs-db psql -U crs -d crs -X -q -P pager=off)

echo "S2: 학생 $COUNT명이 $COURSE에 동시 신청 ($BASE)"
require_server

echo "1) 로그인·입장 준비"
declare -A TOKEN
for i in $(seq 0 $((COUNT - 1))); do
  u=$((FIRST + i))
  login "$u" || { echo "  $u 로그인 실패"; continue; }
  t="$ACCESS"
  req POST /admission "" -H "Authorization: Bearer $t"
  if [ "$STATUS" = 202 ]; then wait_ticket "$(json_get ticketId)" -H "Authorization: Bearer $t"; fi
  TOKEN[$u]="$t"
done
echo "  준비된 학생 ${#TOKEN[@]}명"

echo "2) 동시 신청"
for u in "${!TOKEN[@]}"; do
  curl -s -o "$TMP/enr$u" -X POST "$BASE/me/enrollments" \
       -H "Origin: $ORIGIN" -H "X-CSRF-Protection: 1" -H "Content-Type: application/json" \
       -H "Authorization: Bearer ${TOKEN[$u]}" --data-binary "{\"courseCode\":\"$COURSE\"}" &
done
wait

echo "3) 결과 대기"
declare -A RESULT
for u in "${!TOKEN[@]}"; do
  id="$(sed -n 's/.*"ticketId":"\{0,1\}\([0-9]*\).*/\1/p' "$TMP/enr$u")"
  if [ -n "$id" ] && wait_ticket "$id" -H "Authorization: Bearer ${TOKEN[$u]}"; then
    code="$(printf '%s' "$BODY" | sed -n 's/.*"result":{[^}]*"code":"\([A-Z0-9]*\)".*/\1/p')"
    RESULT[$code]=$(( ${RESULT[$code]:-0} + 1 ))
  else
    RESULT[미종결]=$(( ${RESULT[미종결]:-0} + 1 ))
  fi
done
for k in "${!RESULT[@]}"; do echo "  $k: ${RESULT[$k]}건"; done

echo
echo "== 과목 인원과 ENROLLED 내역"
"${PSQL[@]}" -c "SELECT c.course_code, c.max_capacity, c.active_cnt,
       count(e.student_id) FILTER (WHERE e.enroll_status='ENROLLED') AS enrolled_rows
  FROM courses c LEFT JOIN enrollments e USING (course_inst_id)
 WHERE c.course_code='$COURSE' GROUP BY c.course_code, c.max_capacity, c.active_cnt"
echo "== 결과 코드별 티켓 수"
"${PSQL[@]}" -c "SELECT result_cd, count(*) FROM tickets
 WHERE request_type='ENROLLMENT' AND course_code_snapshot='$COURSE' GROUP BY result_cd ORDER BY result_cd"
echo "== INV-2 검증 쿼리(0행이어야 한다)"
"${PSQL[@]}" -c "SELECT c.course_inst_id FROM courses c
  LEFT JOIN enrollments e ON e.course_inst_id = c.course_inst_id AND e.enroll_status = 'ENROLLED'
 GROUP BY c.course_inst_id, c.active_cnt HAVING c.active_cnt <> count(e.student_id)"
