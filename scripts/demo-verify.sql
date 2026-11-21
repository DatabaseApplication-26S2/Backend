-- Read-only evidence. psql -v course_code=DEMO01 -f scripts/demo-verify.sql
\set ON_ERROR_STOP on
\if :{?course_code}
\else
\set course_code DEMO01
\endif
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SELECT clock_timestamp() AS captured_at;
-- S1/S2: aggregate durable outcomes, then inspect FIFO ticket order.
SELECT request_type,status_cd,result_cd,count(*) AS total
FROM tickets GROUP BY request_type,status_cd,result_cd ORDER BY 1,2,3;
SELECT result_cd,count(*) AS total FROM tickets
WHERE request_type='ENROLLMENT' AND course_code_snapshot=:'course_code'
GROUP BY result_cd ORDER BY result_cd;
-- INV-2: zero rows required (including CLOSED courses).
SELECT c.course_code,c.active_cnt,count(e.student_id) AS actual_count
FROM courses c LEFT JOIN enrollments e
  ON e.course_inst_id=c.course_inst_id AND e.enroll_status='ENROLLED'
GROUP BY c.course_inst_id HAVING c.active_cnt<>count(e.student_id);
-- S4: run before and after closure, save output separately.
SELECT course_inst_id,course_code,course_status,max_capacity,active_cnt,course_updated_at
FROM courses WHERE course_code=:'course_code';
SELECT e.student_id,e.enroll_status,e.enroll_type,e.source_ticket_id
FROM enrollments e JOIN courses c USING(course_inst_id)
WHERE c.course_code=:'course_code' ORDER BY e.student_id;
SELECT ticket_id,request_type,requester_user_id,status_cd,result_cd,generation_no,retry_count
FROM tickets WHERE course_code_snapshot=:'course_code' ORDER BY ticket_id;
COMMIT;
