-- X-02 테이블 기술서 추출 SQL (백엔드 계획 3.10). 읽기 전용이다.
-- 빈 PostgreSQL 17에 V1__schema.sql을 적용한 DB에서 실행해 X-08 문서(논리·물리 데이터 모델, 테이블 기술서)의
-- 컬럼·제약·인덱스·시퀀스 값을 뽑는다. Flyway 이력 테이블은 제외한다.
--   psql -X -v ON_ERROR_STOP=1 -f scripts/schema-catalog.sql             # 표 형식
--   psql -X -v ON_ERROR_STOP=1 --csv -f scripts/schema-catalog.sql       # 문서 작성용 CSV
\set tables '''{users,courses,enrollments,tickets,service_settings,sessions,refresh_tokens}'''

\echo '== 1. 테이블별 컬럼 수'
SELECT c.relname AS table_name,
       count(*) AS columns,
       count(*) FILTER (WHERE a.attnotnull) AS not_null,
       count(*) FILTER (WHERE NOT a.attnotnull) AS nullable
  FROM pg_class c
  JOIN pg_namespace n ON n.oid = c.relnamespace
  JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
 WHERE n.nspname = 'public' AND c.relkind = 'r' AND c.relname = ANY (:tables::text[])
 GROUP BY c.relname
 ORDER BY array_position(:tables::text[], c.relname::text);

\echo '== 2. 컬럼'
SELECT c.relname AS table_name,
       a.attnum AS ordinal,
       a.attname AS column_name,
       format_type(a.atttypid, a.atttypmod) AS data_type,
       CASE WHEN a.attnotnull THEN 'NOT NULL' ELSE 'NULL' END AS nullability,
       pg_get_expr(d.adbin, d.adrelid) AS column_default,
       CASE WHEN a.attcollation <> 0 AND a.attcollation <> t.typcollation THEN co.collname END AS collation
  FROM pg_class c
  JOIN pg_namespace n ON n.oid = c.relnamespace
  JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
  JOIN pg_type t ON t.oid = a.atttypid
  LEFT JOIN pg_attrdef d ON d.adrelid = c.oid AND d.adnum = a.attnum
  LEFT JOIN pg_collation co ON co.oid = a.attcollation
 WHERE n.nspname = 'public' AND c.relkind = 'r' AND c.relname = ANY (:tables::text[])
 ORDER BY array_position(:tables::text[], c.relname::text), a.attnum;

\echo '== 3. 제약 (p=PK, u=UNIQUE, f=FK, c=CHECK)'
SELECT c.relname AS table_name,
       con.contype AS type,
       con.conname AS constraint_name,
       pg_get_constraintdef(con.oid, true) AS definition
  FROM pg_constraint con
  JOIN pg_class c ON c.oid = con.conrelid
  JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE n.nspname = 'public' AND c.relname = ANY (:tables::text[])
 ORDER BY array_position(:tables::text[], c.relname::text),
          array_position(ARRAY['p','u','f','c']::"char"[], con.contype),
          con.conname;

\echo '== 4. 인덱스 (constraint_backed = PK·UNIQUE 제약이 만든 인덱스)'
SELECT t.relname AS table_name,
       i.relname AS index_name,
       ix.indisunique AS is_unique,
       EXISTS (SELECT 1 FROM pg_constraint con WHERE con.conindid = i.oid) AS constraint_backed,
       pg_get_expr(ix.indpred, ix.indrelid) AS partial_predicate,
       pg_get_indexdef(i.oid) AS definition
  FROM pg_index ix
  JOIN pg_class i ON i.oid = ix.indexrelid
  JOIN pg_class t ON t.oid = ix.indrelid
  JOIN pg_namespace n ON n.oid = t.relnamespace
 WHERE n.nspname = 'public' AND t.relname = ANY (:tables::text[])
 ORDER BY array_position(:tables::text[], t.relname::text), constraint_backed DESC, i.relname;

\echo '== 5. 시퀀스'
SELECT s.relname AS sequence_name,
       format_type(sq.seqtypid, NULL) AS data_type,
       sq.seqstart AS start_value,
       sq.seqincrement AS increment,
       (SELECT string_agg(c.relname || '.' || a.attname, ', ')
          FROM pg_depend d
          JOIN pg_class c ON c.oid = d.refobjid
          JOIN pg_attribute a ON a.attrelid = d.refobjid AND a.attnum = d.refobjsubid
         WHERE d.objid = s.oid AND d.deptype = 'a') AS owned_by
  FROM pg_sequence sq
  JOIN pg_class s ON s.oid = sq.seqrelid
  JOIN pg_namespace n ON n.oid = s.relnamespace
 WHERE n.nspname = 'public'
 ORDER BY s.relname;

\echo '== 6. 합계'
SELECT (SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'public' AND c.relkind = 'r' AND c.relname = ANY (:tables::text[])) AS tables,
       (SELECT count(*) FROM pg_attribute a JOIN pg_class c ON c.oid = a.attrelid
          JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'public' AND c.relname = ANY (:tables::text[]) AND a.attnum > 0 AND NOT a.attisdropped) AS columns,
       (SELECT count(*) FROM pg_attribute a JOIN pg_class c ON c.oid = a.attrelid
          JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'public' AND c.relname = ANY (:tables::text[]) AND a.attnum > 0 AND NOT a.attisdropped
           AND a.attnotnull) AS not_null_columns,
       (SELECT count(*) FROM pg_constraint con JOIN pg_class c ON c.oid = con.conrelid
          JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'public' AND c.relname = ANY (:tables::text[])) AS constraints,
       (SELECT count(*) FROM pg_constraint con JOIN pg_class c ON c.oid = con.conrelid
          JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'public' AND c.relname = ANY (:tables::text[]) AND con.contype = 'p') AS primary_keys,
       (SELECT count(*) FROM pg_constraint con JOIN pg_class c ON c.oid = con.conrelid
          JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'public' AND c.relname = ANY (:tables::text[]) AND con.contype = 'u') AS uniques,
       (SELECT count(*) FROM pg_constraint con JOIN pg_class c ON c.oid = con.conrelid
          JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'public' AND c.relname = ANY (:tables::text[]) AND con.contype = 'f') AS foreign_keys,
       (SELECT count(*) FROM pg_constraint con JOIN pg_class c ON c.oid = con.conrelid
          JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'public' AND c.relname = ANY (:tables::text[]) AND con.contype = 'c') AS checks,
       (SELECT count(*) FROM pg_index ix JOIN pg_class t ON t.oid = ix.indrelid
          JOIN pg_namespace n ON n.oid = t.relnamespace
         WHERE n.nspname = 'public' AND t.relname = ANY (:tables::text[])) AS indexes,
       (SELECT count(*) FROM pg_index ix JOIN pg_class t ON t.oid = ix.indrelid
          JOIN pg_namespace n ON n.oid = t.relnamespace
         WHERE n.nspname = 'public' AND t.relname = ANY (:tables::text[])
           AND NOT EXISTS (SELECT 1 FROM pg_constraint con WHERE con.conindid = ix.indexrelid)) AS standalone_indexes,
       (SELECT count(*) FROM pg_sequence sq JOIN pg_class s ON s.oid = sq.seqrelid
          JOIN pg_namespace n ON n.oid = s.relnamespace WHERE n.nspname = 'public') AS sequences;
