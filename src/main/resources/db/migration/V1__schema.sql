-- =====================================================================
-- V1__schema.sql — 구현계획-backend.md 3절 물리 DB 설계(테이블 7개, 컬럼 63개)
--   users · courses · enrollments · tickets · service_settings · sessions · refresh_tokens
-- 물리명은 도메인정의서 5장, 값 집합·조건부 필수·유일성은 도메인정의서 4·6장을 따른다.
-- M2(11/18) DDL 동결 이후에는 이 파일을 고치지 않고 V2 이후로 추가한다.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 3.1 users — 4개
-- ---------------------------------------------------------------------
CREATE TABLE users (
    user_id        varchar(20)  PRIMARY KEY,
    password_hash  varchar(100) NOT NULL,
    user_name      varchar(50)  NOT NULL,
    role_cd        varchar(20)  NOT NULL CHECK (role_cd IN ('STUDENT', 'PROFESSOR', 'ADMIN'))
);

-- ---------------------------------------------------------------------
-- 3.2 courses — 13개
-- ---------------------------------------------------------------------
CREATE TABLE courses (
    course_inst_id     uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    course_code        varchar(10)  COLLATE "C" NOT NULL UNIQUE
                                    CHECK (course_code ~ '^[A-Za-z0-9]{1,10}$'),
    professor_id       varchar(20)  NOT NULL REFERENCES users (user_id),
    course_title       text         NOT NULL CHECK (course_title ~ '\S'),
    course_category    varchar(20)  NOT NULL CHECK (course_category IN ('GENERAL', 'MAJOR')),
    grading_type       varchar(20)  NOT NULL CHECK (grading_type IN ('RELATIVE', 'ABSOLUTE', 'PASS_FAIL')),
    max_capacity       integer      NOT NULL CHECK (max_capacity >= 1),
    min_enrollment     integer      NOT NULL,
    active_cnt         integer      NOT NULL DEFAULT 0 CHECK (active_cnt >= 0),
    classroom          text         NOT NULL CHECK (classroom ~ '\S'),
    course_status      varchar(20)  NOT NULL DEFAULT 'OPEN' CHECK (course_status IN ('OPEN', 'CLOSED')),
    course_created_at  timestamptz  NOT NULL,
    course_updated_at  timestamptz  NULL,
    -- 최소 수강인원은 1 이상 정원 이하
    CONSTRAINT ck_courses_min_enrollment CHECK (min_enrollment BETWEEN 1 AND max_capacity),
    -- INV-8: 폐강 과목의 인원은 0
    CONSTRAINT ck_courses_closed_active  CHECK (course_status = 'OPEN' OR active_cnt = 0)
    -- 인원 ≤ 정원 CHECK는 두지 않는다(관리자 배정이 정원을 넘길 수 있음, 3.9)
);

-- ---------------------------------------------------------------------
-- 3.4 tickets — 20개 (sessions와 서로 참조하므로 sessions FK는 아래에서 붙인다)
-- ---------------------------------------------------------------------
CREATE SEQUENCE ticket_id_seq;

CREATE TABLE tickets (
    ticket_id               bigint       PRIMARY KEY DEFAULT nextval('ticket_id_seq') CHECK (ticket_id > 0),
    request_type            varchar(20)  NOT NULL
                                         CHECK (request_type IN ('LOGIN', 'ADMISSION', 'ENROLLMENT', 'CANCELLATION')),
    requester_user_id       varchar(20)  NULL REFERENCES users (user_id),
    session_ref             uuid         NULL,
    anon_context_id         uuid         NULL,
    anon_proof_verifier     char(64)     NULL UNIQUE,
    credential_expires_at   timestamptz  NULL,
    proof_expires_at        timestamptz  NULL,
    target_course_inst_id   uuid         NULL REFERENCES courses (course_inst_id),
    course_code_snapshot    varchar(10)  NULL,
    ticket_issued_at        timestamptz  NOT NULL,
    status_cd               varchar(20)  NOT NULL DEFAULT 'RECEIVED'
                                         CHECK (status_cd IN ('RECEIVED', 'WAITING', 'PROCESSING', 'SUCCEEDED', 'REJECTED')),
    processing_started_at   timestamptz  NULL,
    processing_finished_at  timestamptz  NULL,
    result_cd               varchar(10)  NULL,
    retry_count             integer      NOT NULL DEFAULT 0 CHECK (retry_count >= 0),
    owner_worker_ref        varchar(64)  NULL,
    generation_no           integer      NOT NULL DEFAULT 0,
    lease_info              timestamptz  NULL,
    slot_claim_ref          integer      NULL,

    -- 1. LOGIN이면 익명 4개 컬럼이 모두 있고 요청자·세션이 없다. 그 밖의 유형은 반대다
    CONSTRAINT ck_tickets_login_fields CHECK (
        (request_type = 'LOGIN'
           AND anon_context_id IS NOT NULL AND anon_proof_verifier IS NOT NULL
           AND credential_expires_at IS NOT NULL AND proof_expires_at IS NOT NULL
           AND requester_user_id IS NULL AND session_ref IS NULL)
        OR
        (request_type <> 'LOGIN'
           AND anon_context_id IS NULL AND anon_proof_verifier IS NULL
           AND credential_expires_at IS NULL AND proof_expires_at IS NULL
           AND requester_user_id IS NOT NULL AND session_ref IS NOT NULL)
    ),
    -- 2. ENROLLMENT·CANCELLATION만 대상 과목과 코드 기록이 있다
    CONSTRAINT ck_tickets_target_fields CHECK (
        (request_type IN ('ENROLLMENT', 'CANCELLATION')
           AND target_course_inst_id IS NOT NULL AND course_code_snapshot IS NOT NULL)
        OR
        (request_type IN ('LOGIN', 'ADMISSION')
           AND target_course_inst_id IS NULL AND course_code_snapshot IS NULL)
    ),
    -- 3. PROCESSING일 때만 소유·리스·슬롯이 있고, 슬롯 값은 현재 세대와 같다
    CONSTRAINT ck_tickets_processing_fields CHECK (
        (status_cd = 'PROCESSING'
           AND owner_worker_ref IS NOT NULL AND lease_info IS NOT NULL
           AND slot_claim_ref IS NOT NULL AND slot_claim_ref = generation_no)
        OR
        (status_cd <> 'PROCESSING'
           AND owner_worker_ref IS NULL AND lease_info IS NULL AND slot_claim_ref IS NULL)
    ),
    -- 4. 종결일 때만 결과와 완료 시각이 있다. SUCCEEDED는 OK, REJECTED는 OK가 아니다
    CONSTRAINT ck_tickets_result_fields CHECK (
        (status_cd = 'SUCCEEDED'
           AND result_cd IS NOT NULL AND result_cd = 'OK'
           AND processing_finished_at IS NOT NULL)
        OR
        (status_cd = 'REJECTED'
           AND result_cd IS NOT NULL AND result_cd <> 'OK'
           AND processing_finished_at IS NOT NULL)
        OR
        (status_cd IN ('RECEIVED', 'WAITING', 'PROCESSING')
           AND result_cd IS NULL AND processing_finished_at IS NULL)
    ),
    -- 5. 요청 유형별 허용 결과 코드(도메인정의서 4.9)
    CONSTRAINT ck_tickets_result_by_type CHECK (
        result_cd IS NULL
        OR (request_type = 'LOGIN'        AND result_cd IN ('OK', 'E01', 'E99'))
        OR (request_type = 'ADMISSION'    AND result_cd IN ('OK', 'E02', 'E99'))
        OR (request_type = 'ENROLLMENT'   AND result_cd IN ('OK', 'E02', 'E04', 'E05', 'E06', 'E07', 'E15', 'E99'))
        OR (request_type = 'CANCELLATION' AND result_cd IN ('OK', 'E02', 'E04', 'E05', 'E08', 'E15', 'E99'))
    )
);

-- ---------------------------------------------------------------------
-- 3.6 sessions — 11개
-- ---------------------------------------------------------------------
CREATE TABLE sessions (
    session_id             uuid         PRIMARY KEY,
    user_id                varchar(20)  NOT NULL REFERENCES users (user_id),
    role_cd                varchar(20)  NOT NULL CHECK (role_cd IN ('STUDENT', 'PROFESSOR', 'ADMIN')),
    login_ticket_id        bigint       NOT NULL UNIQUE REFERENCES tickets (ticket_id),   -- R11
    logged_in_at           timestamptz  NOT NULL,
    -- 고정 7일 = 168시간(interval '7 days'는 일광 절약 시간에 따라 달라질 수 있어 쓰지 않는다)
    expires_at             timestamptz  NOT NULL CHECK (expires_at = logged_in_at + interval '168 hours'),
    session_status         varchar(20)  NOT NULL DEFAULT 'ACTIVE' CHECK (session_status IN ('ACTIVE', 'REVOKED')),
    revoked_at             timestamptz  NULL,
    revoke_reason          varchar(20)  NULL CHECK (revoke_reason IN ('LOGOUT', 'REFRESH_REUSE')),
    first_token_issued_at  timestamptz  NULL,
    entered_at             timestamptz  NULL CHECK (entered_at IS NULL OR role_cd = 'STUDENT'),
    -- REVOKED일 때만 폐기 시각과 사유가 있다
    CONSTRAINT ck_sessions_revoked_fields CHECK (
        (session_status = 'REVOKED' AND revoked_at IS NOT NULL AND revoke_reason IS NOT NULL)
        OR
        (session_status = 'ACTIVE' AND revoked_at IS NULL AND revoke_reason IS NULL)
    )
);

-- tickets ↔ sessions는 서로를 가리키므로 두 테이블을 만든 뒤 FK를 붙인다
ALTER TABLE tickets
    ADD CONSTRAINT fk_tickets_session_ref FOREIGN KEY (session_ref) REFERENCES sessions (session_id);

-- ---------------------------------------------------------------------
-- 3.7 refresh_tokens — 4개
-- ---------------------------------------------------------------------
CREATE TABLE refresh_tokens (
    refresh_token_hash  char(64)     PRIMARY KEY,               -- SHA-256 16진(D-08)
    session_id          uuid         NOT NULL REFERENCES sessions (session_id),
    issued_at           timestamptz  NOT NULL,
    consumed_at         timestamptz  NULL
);

-- ---------------------------------------------------------------------
-- 3.3 enrollments — 7개
-- ---------------------------------------------------------------------
CREATE TABLE enrollments (
    student_id           varchar(20)  NOT NULL REFERENCES users (user_id),
    course_inst_id       uuid         NOT NULL REFERENCES courses (course_inst_id),
    enroll_status        varchar(20)  NOT NULL DEFAULT 'ENROLLED' CHECK (enroll_status IN ('ENROLLED', 'CLOSED')),
    enroll_type          varchar(20)  NOT NULL CHECK (enroll_type IN ('STUDENT', 'ADMIN')),
    enrolled_at          timestamptz  NOT NULL,
    assigned_by_user_id  varchar(20)  NULL REFERENCES users (user_id),
    source_ticket_id     bigint       NULL UNIQUE,
    PRIMARY KEY (student_id, course_inst_id),                                         -- INV-4
    -- ADMIN이면 배정 관리자만, STUDENT이면 근거 티켓만 있다(도메인정의서 5.3)
    CONSTRAINT ck_enrollments_type_fields CHECK (
        (enroll_type = 'ADMIN'
           AND assigned_by_user_id IS NOT NULL AND source_ticket_id IS NULL)
        OR
        (enroll_type = 'STUDENT'
           AND source_ticket_id IS NOT NULL AND assigned_by_user_id IS NULL)
    )
);

-- ---------------------------------------------------------------------
-- 3.5 service_settings — 4개 (singleton은 단일 행을 강제하는 물리 장치)
-- ---------------------------------------------------------------------
CREATE TABLE service_settings (
    singleton          boolean      PRIMARY KEY DEFAULT true CHECK (singleton),
    concurrency_limit  integer      NOT NULL DEFAULT 100 CHECK (concurrency_limit BETWEEN 1 AND 1000),
    last_changed_by    varchar(20)  NULL REFERENCES users (user_id),
    last_changed_at    timestamptz  NULL,
    -- 변경자와 변경 시각은 둘 다 있거나 둘 다 없다(MA-05)
    CONSTRAINT ck_service_settings_changed CHECK ((last_changed_by IS NULL) = (last_changed_at IS NULL))
);

-- 초기 행(N = 100, 변경자·시각 없음)은 시드가 아니라 V1이 넣는다
INSERT INTO service_settings (singleton, concurrency_limit) VALUES (true, 100);

-- ---------------------------------------------------------------------
-- 3.8 인덱스
-- ---------------------------------------------------------------------
-- INV-5: 인증 후 3유형은 사용자당 미완료 1건(접수의 ON CONFLICT 대상, E03)
CREATE UNIQUE INDEX ux_tickets_incomplete_per_user
    ON tickets (requester_user_id)
    WHERE status_cd IN ('RECEIVED', 'WAITING', 'PROCESSING') AND request_type <> 'LOGIN';
-- INV-5의 선언적 방어선: 익명 컨텍스트당 미완료 LOGIN 1건
CREATE UNIQUE INDEX ux_tickets_incomplete_login
    ON tickets (anon_context_id)
    WHERE status_cd IN ('RECEIVED', 'WAITING', 'PROCESSING') AND request_type = 'LOGIN';
-- enrollments 복합 FK의 대상
ALTER TABLE tickets
    ADD CONSTRAINT uq_tickets_owner_target UNIQUE (ticket_id, requester_user_id, target_course_inst_id);
-- claim, 순번, 대기 수
CREATE INDEX ix_tickets_waiting ON tickets (ticket_id) WHERE status_cd = 'WAITING';
-- 같은 과목의 선행 티켓 확인
CREATE INDEX ix_tickets_course_pending
    ON tickets (target_course_inst_id, ticket_id) WHERE status_cd IN ('WAITING', 'PROCESSING');
-- A 집계, 복구 후보
CREATE INDEX ix_tickets_lease ON tickets (lease_info) WHERE status_cd = 'PROCESSING';
-- 과목별 현재 신청 행 조회
CREATE INDEX ix_enrollments_course_enrolled ON enrollments (course_inst_id) WHERE enroll_status = 'ENROLLED';
-- 사용자별 세션 조회
CREATE INDEX ix_sessions_user ON sessions (user_id);
-- R12: 세션당 미소비 Refresh 기록 1건(회전할 때 소비 표시가 새 기록 INSERT보다 먼저여야 한다)
CREATE UNIQUE INDEX ux_refresh_tokens_unconsumed ON refresh_tokens (session_id) WHERE consumed_at IS NULL;

-- R05의 "같은 학생·같은 과목"을 선언으로 강제한다(MATCH SIMPLE: ADMIN 행은 source_ticket_id가 NULL이라 검사하지 않음)
ALTER TABLE enrollments
    ADD CONSTRAINT fk_enrollments_source_ticket
    FOREIGN KEY (source_ticket_id, student_id, course_inst_id)
    REFERENCES tickets (ticket_id, requester_user_id, target_course_inst_id);
