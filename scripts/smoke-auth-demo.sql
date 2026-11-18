-- smoke-p1.sh supplement for a disposable DB initialized with local/demo V900.
-- Run once after application startup. Existing team databases are not the target.
-- Accounts/passwords come from V900; no plaintext password is inserted here.
INSERT INTO courses(course_code,professor_id,course_title,course_category,grading_type,
                    max_capacity,min_enrollment,classroom,course_status,course_created_at)
VALUES
 ('CSE4020','P1001','컴파일러','MAJOR','ABSOLUTE',30,6,'공학관 402','OPEN',clock_timestamp()),
 ('SMKCLOSED','P1001','통합 점검 폐강 과목','MAJOR','ABSOLUTE',30,1,'점검실','CLOSED',clock_timestamp());
