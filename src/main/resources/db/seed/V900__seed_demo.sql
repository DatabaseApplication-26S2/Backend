-- Local/demo only. D-20 provisional values are documented in TESTING.md.
INSERT INTO users(user_id,password_hash,user_name,role_cd)
SELECT (20260000+n)::text, '$2a$10$2Wg.w665DTnriOHgXlwMY.HnEr/3Gp1WP2xLplNz6Yqdc5MEFp8Bm', '시연 학생 '||n, 'STUDENT'
FROM generate_series(1,60) n;
INSERT INTO users(user_id,password_hash,user_name,role_cd)
SELECT 'P'||(1000+n), '$2a$10$2Wg.w665DTnriOHgXlwMY.HnEr/3Gp1WP2xLplNz6Yqdc5MEFp8Bm', '시연 교수 '||n, 'PROFESSOR'
FROM generate_series(1,3) n;
INSERT INTO users VALUES ('A1001','$2a$10$2Wg.w665DTnriOHgXlwMY.HnEr/3Gp1WP2xLplNz6Yqdc5MEFp8Bm','시연 관리자','ADMIN');
INSERT INTO courses(course_code,professor_id,course_title,course_category,grading_type,max_capacity,min_enrollment,classroom,course_created_at)
SELECT 'DEMO'||lpad(n::text,2,'0'), 'P'||(1001+(n-1)%3), '시연 과목 '||n,
 CASE WHEN n%2=0 THEN 'GENERAL' ELSE 'MAJOR' END, 'ABSOLUTE',
 CASE n WHEN 1 THEN 3 WHEN 2 THEN 1 ELSE 30 END, 1, '시연 강의실 '||n, clock_timestamp()
FROM generate_series(1,25) n;
INSERT INTO enrollments(student_id,course_inst_id,enroll_type,enrolled_at,assigned_by_user_id)
SELECT '20260001',course_inst_id,'ADMIN',clock_timestamp(),'A1001' FROM courses WHERE course_code='DEMO03';
UPDATE courses SET active_cnt=1 WHERE course_code='DEMO03';
