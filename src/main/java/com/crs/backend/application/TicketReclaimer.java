package com.crs.backend.application;

import com.crs.backend.adapter.out.persistence.jpa.ServiceSettingsRepository;
import com.crs.backend.domain.entity.ServiceSettings;
import com.crs.backend.domain.entity.WaitingTicket;
import com.crs.backend.queue.QueueProperties;
import com.crs.backend.queue.RedisWaitingOrderCopy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * 3단계 재투입 회수기(구현계획 4절 (8), BE-17).
 * <ul>
 *   <li>주기 점검(2): 리스가 만료된 PROCESSING 티켓을 회수한다. 점검 주기 10초(D-07).</li>
 *   <li>기동 시 재조정(1): 소유자가 현재 인스턴스가 아닌 PROCESSING 티켓을 리스와 무관하게 회수한다.
 *       단일 인스턴스이고 이전 프로세스의 연결은 끊겼으므로 그 티켓을 쥔 활성 트랜잭션이 없다.</li>
 * </ul>
 * 두 경우 모두 한 트랜잭션에서 회계 잠금을 얻은 뒤 대상 행을 {@code FOR UPDATE SKIP LOCKED}로 고른다. 실행 중인 핸들러가
 * 잠근 행은 기다리지 않고 건너뛴다. 회수(3)는 세대·상태 조건부 UPDATE이므로 동시에 끝난 종결이 이기면 0행이 되어
 * 슬롯을 두 번 반납하지 않는다. WAITING으로 돌린 티켓은 커밋 뒤 같은 번호로 Redis 대기 순서에 다시 넣는다.
 */
@Component
public class TicketReclaimer {

    private static final Logger log = LoggerFactory.getLogger(TicketReclaimer.class);

    /** 2. 리스 만료 대상. 활성 트랜잭션이 잠근 행은 건너뛴다 */
    private static final String SELECT_EXPIRED = """
            SELECT ticket_id, generation_no, request_type, retry_count
              FROM tickets
             WHERE status_cd = 'PROCESSING' AND lease_info < clock_timestamp()
             ORDER BY ticket_id
               FOR UPDATE SKIP LOCKED
            """;

    /** 1. 기동 시 대상: 이전 인스턴스(현재 WORKER_REF가 아닌 소유자)가 남긴 PROCESSING 티켓 */
    private static final String SELECT_ORPHANS = """
            SELECT ticket_id, generation_no, request_type, retry_count
              FROM tickets
             WHERE status_cd = 'PROCESSING' AND owner_worker_ref <> ?
             ORDER BY ticket_id
               FOR UPDATE SKIP LOCKED
            """;

    /** 3. 한도 미만: 세대 +1, 소유·리스·슬롯을 비우고 같은 번호로 WAITING 복귀, 재투입 횟수 +1 */
    private static final String RETURN_TO_WAITING = """
            UPDATE tickets
               SET generation_no = generation_no + 1,
                   owner_worker_ref = NULL, lease_info = NULL, slot_claim_ref = NULL,
                   status_cd = 'WAITING', retry_count = retry_count + 1,
                   processing_started_at = NULL
             WHERE ticket_id = ? AND generation_no = ? AND status_cd = 'PROCESSING'
            """;

    /** 3. 한도 도달: 세대 +1, 소유·리스·슬롯을 비우고 REJECTED/E99로 종결. 종결 시각은 DB 시계다 */
    private static final String REJECT_E99 = """
            UPDATE tickets
               SET generation_no = generation_no + 1,
                   owner_worker_ref = NULL, lease_info = NULL, slot_claim_ref = NULL,
                   status_cd = 'REJECTED', result_cd = 'E99',
                   processing_finished_at = clock_timestamp()
             WHERE ticket_id = ? AND generation_no = ? AND status_cd = 'PROCESSING'
            """;

    private static final RowMapper<Candidate> CANDIDATE = (rs, rowNum) -> new Candidate(
            rs.getLong("ticket_id"),
            rs.getInt("generation_no"),
            rs.getString("request_type"),
            rs.getInt("retry_count")
    );

    private final ServiceSettingsRepository serviceSettingsRepository;
    private final JdbcTemplate jdbc;
    private final RedisWaitingOrderCopy waitingOrderCopy;
    private final QueueProperties properties;
    private final TransactionTemplate reclaimTx;

    public TicketReclaimer(
            ServiceSettingsRepository serviceSettingsRepository,
            JdbcTemplate jdbc,
            RedisWaitingOrderCopy waitingOrderCopy,
            QueueProperties properties,
            PlatformTransactionManager transactionManager
    ) {
        this.serviceSettingsRepository = serviceSettingsRepository;
        this.jdbc = jdbc;
        this.waitingOrderCopy = waitingOrderCopy;
        this.properties = properties;
        this.reclaimTx = new TransactionTemplate(transactionManager);
    }

    /** 주기 점검(구현계획 4절 (8) 2). 리스가 만료된 PROCESSING 티켓을 회수하고 회수한 행 수(WAITING + REJECTED)를 돌려준다 */
    @Scheduled(
            initialDelayString = "${queue.reclaim-fixed-delay-ms:10000}",
            fixedDelayString = "${queue.reclaim-fixed-delay-ms:10000}"
    )
    public int reclaimExpired() {
        return reclaim("lease-expired", SELECT_EXPIRED);
    }

    /**
     * 기동 시 재조정(구현계획 4절 (8) 1). 다른 인스턴스 소유의 PROCESSING 티켓을 리스와 무관하게 회수하고 행 수를 돌려준다.
     * DB 오류나 service_settings 행 누락은 그대로 던진다. 기동을 막지 않는 처리는 {@link #reclaimOrphansOnStartup}이 맡는다.
     */
    public int reclaimOrphans() {
        return reclaim("orphaned", SELECT_ORPHANS, WaitingTicket.WORKER_REF);
    }

    /**
     * {@code queue.reclaim-on-startup}이 켜져 있으면 기동 직후 {@link #reclaimOrphans}를 한 번 부른다.
     * 실패해도 기동은 계속한다. 이전 프로세스가 죽었으므로 그 티켓의 리스는 더 연장되지 않고 늦어도 30초 안에 끝나며,
     * 그 뒤 주기 점검 {@link #reclaimExpired}가 같은 규칙으로 회수한다. 로그에는 티켓 번호와 사용자 정보를 남기지 않는다.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void reclaimOrphansOnStartup() {
        if (!properties.isReclaimOnStartup()) {
            return;
        }
        try {
            reclaimOrphans();
        } catch (RuntimeException ex) {
            // 예외 메시지에 행 값(Failing row contains ...)이 들어갈 수 있어 예외 유형만 남긴다(INV-10)
            log.error("Startup reclaim of orphaned PROCESSING tickets failed ({}); the periodic lease-expiry check"
                    + " will reclaim them once their leases (at most 30 s, no longer renewed) expire",
                    ex.getClass().getName());
        }
    }

    private int reclaim(String reason, String selectSql, Object... selectArgs) {
        List<Reclaimed> reclaimed = reclaimTx.execute(status -> reclaimLocked(selectSql, selectArgs));
        if (reclaimed == null) {
            return 0;
        }
        // 커밋한 뒤에만 남긴다. 티켓 번호·유형·세대·횟수만 쓰고 사용자 정보는 남기지 않는다(INV-10)
        for (Reclaimed r : reclaimed) {
            if (r.rejected()) {
                log.warn("Reclaimed {} ticket {} ({}) at generation {}: REJECTED E99 after {} retries",
                        reason, r.ticketId(), r.requestType(), r.generation(), r.retryCount());
            } else {
                log.warn("Reclaimed {} ticket {} ({}) at generation {}: WAITING, retry {}/{}",
                        reason, r.ticketId(), r.requestType(), r.generation(), r.retryCount() + 1,
                        WaitingTicket.MAX_RETRY);
            }
        }
        return reclaimed.size();
    }

    private List<Reclaimed> reclaimLocked(String selectSql, Object... selectArgs) {
        // 회계 잠금(구현계획 4절 (3)). 회수는 PROCESSING 수를 줄이므로 claim의 N′ 계산과 직렬화한다
        serviceSettingsRepository.findByIdForUpdate(ServiceSettings.SINGLETON_ID)
                .orElseThrow(() -> new IllegalStateException("service_settings singleton row is missing"));

        List<Candidate> candidates = jdbc.query(selectSql, CANDIDATE, selectArgs);
        List<Reclaimed> reclaimed = new ArrayList<>(candidates.size());
        for (Candidate c : candidates) {
            boolean reject = c.retryCount() >= WaitingTicket.MAX_RETRY;
            int updated = jdbc.update(reject ? REJECT_E99 : RETURN_TO_WAITING, c.ticketId(), c.generation());
            if (updated == 0) {
                continue;
            }
            if (!reject) {
                // 재투입해도 번호를 유지한다(INV-7). 커밋 뒤 같은 번호로 대기 순서 사본에 다시 넣는다(구현계획 4절 (11))
                waitingOrderCopy.publishAfterCommit(c.ticketId());
            }
            reclaimed.add(new Reclaimed(c.ticketId(), c.requestType(), c.generation(), c.retryCount(), reject));
        }
        return reclaimed;
    }

    private record Candidate(long ticketId, int generation, String requestType, int retryCount) {
    }

    private record Reclaimed(long ticketId, String requestType, int generation, int retryCount, boolean rejected) {
    }
}
