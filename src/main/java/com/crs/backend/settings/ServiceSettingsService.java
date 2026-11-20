package com.crs.backend.settings;

import com.crs.backend.auth.CurrentUser;
import com.crs.backend.common.ApiProblem.Source;
import com.crs.backend.common.CrsException;
import com.crs.backend.domain.enums.UserRole;
import java.sql.Timestamp;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * ADM-02-01 서비스 설정(동시 처리 수 N) 조회·변경(BE-14).
 *
 * 판정 순서(openapi.yaml):
 *   GET  Access(401, 필터) → ADMIN(403) → 쿼리 없음(400 E09, ContractStrictnessInterceptor) → 200
 *   PUT  Access(401, 필터) → ADMIN(403) → 본문(400 E09) → 200
 *
 * N 변경은 claim과 같은 회계 잠금(service_settings 행)을 잡는다(구현계획 4절 (3)).
 * 그래서 진행 중인 claim과 직렬화되고, 커밋 뒤 첫 claim부터 새 N이 적용된다.
 *   - 높이면: 커밋 직후 디스패처를 깨워 늘어난 여유만큼 곧바로 claim한다(DispatchKick).
 *   - 낮추면: 진행 중 작업은 끝까지 간다. PROCESSING 수 ≥ N인 동안 N′ = 0이라 신규 claim이 없다.
 *   - 같은 값이면 아무것도 바꾸지 않는다(변경자·시각 유지).
 */
@Service
public class ServiceSettingsService {

    static final int MIN_LIMIT = 1;
    static final int MAX_LIMIT = 1000;
    static final String RANGE_MESSAGE = "1 이상 1000 이하의 정수여야 합니다.";

    private final JdbcClient jdbc;
    private final DispatchKick kick;
    private final TransactionTemplate writeTx;
    private final TransactionTemplate readTx;

    public ServiceSettingsService(JdbcClient jdbc, DispatchKick kick, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.kick = kick;
        this.writeTx = new TransactionTemplate(txManager);
        this.readTx = new TransactionTemplate(txManager);
        this.readTx.setReadOnly(true);
        this.readTx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    /** GET /admin/settings */
    public ServiceSettingsView get(CurrentUser user) {
        requireAdmin(user);
        return snapshot();
    }

    /** PUT /admin/settings */
    public ServiceSettingsView update(CurrentUser user, ServiceSettingsUpdateRequest req) {
        requireAdmin(user);
        int n = validLimit(req);

        Boolean changed = writeTx.execute(status -> {
            // 1. 회계 잠금: claim(TicketDispatcher)이 잡는 것과 같은 행
            int current = jdbc.sql("SELECT concurrency_limit FROM service_settings WHERE singleton FOR NO KEY UPDATE")
                    .query(Integer.class).single();
            if (current == n) {
                return false; // 같은 값: 변경자·시각을 유지한다
            }
            // 2. 새 N, 변경자, 변경 시각을 한 문장으로 확정한다
            jdbc.sql("""
                            UPDATE service_settings
                               SET concurrency_limit = :n, last_changed_by = :admin, last_changed_at = clock_timestamp()
                             WHERE singleton""")
                    .param("n", n).param("admin", user.userId())
                    .update();
            return true;
        });

        if (Boolean.TRUE.equals(changed)) {
            kick.kick(); // 3. 커밋 뒤: 새 N으로 곧바로 claim
        }
        return snapshot(); // 4. 변경 후 설정과 현황
    }

    /** 설정과 슬롯·대기 현황을 한 스냅샷에서 읽는다 */
    private ServiceSettingsView snapshot() {
        return readTx.execute(status -> jdbc.sql("""
                        SELECT s.concurrency_limit, s.last_changed_by, s.last_changed_at,
                               (SELECT count(*) FROM tickets WHERE status_cd = 'PROCESSING') AS active_slots,
                               (SELECT count(*) FROM tickets WHERE status_cd = 'WAITING')    AS waiting_count
                          FROM service_settings s
                         WHERE s.singleton""")
                .query((rs, i) -> {
                    int limit = rs.getInt("concurrency_limit");
                    int active = rs.getInt("active_slots");
                    Timestamp changedAt = rs.getTimestamp("last_changed_at");
                    return new ServiceSettingsView(limit, active, Math.max(limit - active, 0),
                            rs.getInt("waiting_count"), rs.getString("last_changed_by"),
                            changedAt == null ? null : changedAt.toInstant().toString());
                })
                .single());
    }

    private static void requireAdmin(CurrentUser user) {
        if (user == null || user.role() != UserRole.ADMIN) {
            throw CrsException.forbidden(); // 보안 필터도 /admin/**을 막지만 한 번 더 확인
        }
    }

    /**
     * 본문의 N을 판정한다(openapi.yaml concurrencyLimit: type: integer, 1~1000 / ADM-02-01 "N 범위·정수 조건 위반 → 400 E09").
     *   - 빠짐·null                                   → 필수 항목
     *   - 숫자가 아님("5", true, {}, [5])·정수가 아닌 수(1.5) → 범위·정수 위반
     *   - 정수 값의 소수 표기(20.0)는 JSON Schema의 integer처럼 정수로 받고 범위를 본다
     */
    private static int validLimit(ServiceSettingsUpdateRequest req) {
        JsonNode value = req == null ? null : req.concurrencyLimit();
        if (value == null || value.isNull()) {
            throw CrsException.invalid(Source.BODY, "/concurrencyLimit", "필수 항목입니다.");
        }
        if (!value.isNumber() || !value.canConvertToExactIntegral() || !value.canConvertToInt()) {
            throw CrsException.invalid(Source.BODY, "/concurrencyLimit", RANGE_MESSAGE);
        }
        int n = value.asInt();
        if (n < MIN_LIMIT || n > MAX_LIMIT) {
            throw CrsException.invalid(Source.BODY, "/concurrencyLimit", RANGE_MESSAGE);
        }
        return n;
    }
}
