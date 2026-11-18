-- Destructive DEMO reset. Stop the application/workers first; do not run on a shared production DB.
BEGIN;
TRUNCATE TABLE enrollments,refresh_tokens,sessions,tickets;
ALTER SEQUENCE ticket_id_seq RESTART WITH 1;
UPDATE courses SET active_cnt=0;
UPDATE service_settings SET concurrency_limit=2,last_changed_by=NULL,last_changed_at=NULL WHERE singleton=true;
COMMIT;
-- INV-2: must return no rows.
SELECT c.course_code,c.active_cnt,count(e.student_id) AS actual_count
FROM courses c LEFT JOIN enrollments e ON e.course_inst_id=c.course_inst_id AND e.enroll_status='ENROLLED'
GROUP BY c.course_inst_id HAVING c.active_cnt<>count(e.student_id);
-- Before restarting workers: redis-cli DEL waiting_queue active_users (or configured keys).
