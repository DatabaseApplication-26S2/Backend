package com.crs.backend.application;

import com.crs.backend.domain.entity.WaitingTicket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 리스 연장(구현계획 4절 (8)). 이 인스턴스가 claim했고 아직 끝나지 않은 작업의 리스를 점검 주기마다
 * {@code clock_timestamp() + LEASE}로 늘려, 차례를 기다리는 작업이 회수기의 만료 점검(2)에 걸리지 않게 한다.
 * <p>
 * 대상은 디스패처가 claim 커밋 직후 {@link #track}하고 핸들러가 끝나면 {@link #untrack}한 (티켓, 세대)뿐이다.
 * 핸들러가 자기 행을 잠그고 실행 중이면 {@code SKIP LOCKED}로 건너뛰어 기다리지 않는다. 그 행은 회수기도 건너뛰고,
 * 핸들러는 종결이나 WAITING 복귀로 PROCESSING을 벗어나거나, 롤백되면 연장이 멈춰 30초 뒤 회수된다.
 */
@Component
public class TicketLeaseRenewer {

    private static final Logger log = LoggerFactory.getLogger(TicketLeaseRenewer.class);

    /**
     * 세대 조건부 리스 연장. 추적 중인 (ticket_id, generation_no) 쌍과 같고, 아직 이 인스턴스 소유의 PROCESSING인 행만 고른다.
     * 다른 트랜잭션(실행 중인 핸들러, 회수기)이 잠근 행은 건너뛴다. 시각은 JVM 시계가 아니라 DB 시계다.
     */
    private static final String RENEW_SQL = """
            WITH picked AS (
                SELECT t.ticket_id
                  FROM tickets t
                  JOIN unnest(?::bigint[], ?::integer[]) AS k (ticket_id, generation_no)
                    ON k.ticket_id = t.ticket_id AND k.generation_no = t.generation_no
                 WHERE t.status_cd = 'PROCESSING' AND t.owner_worker_ref = ?
                 ORDER BY t.ticket_id
                   FOR UPDATE OF t SKIP LOCKED
            )
            UPDATE tickets t
               SET lease_info = clock_timestamp() + make_interval(secs => ?)
              FROM picked p
             WHERE t.ticket_id = p.ticket_id
            """;

    private final JdbcTemplate jdbc;
    private final Set<Claim> tracked = ConcurrentHashMap.newKeySet();

    public TicketLeaseRenewer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void track(long ticketId, int generation) {
        tracked.add(new Claim(ticketId, generation));
    }

    public void untrack(long ticketId, int generation) {
        tracked.remove(new Claim(ticketId, generation));
    }

    @Scheduled(
            initialDelayString = "${queue.lease-renew-fixed-delay-ms:10000}",
            fixedDelayString = "${queue.lease-renew-fixed-delay-ms:10000}"
    )
    public int renewLeases() {
        Claim[] claims = tracked.toArray(Claim[]::new);
        if (claims.length == 0) {
            return 0;
        }
        Long[] ticketIds = new Long[claims.length];
        Integer[] generations = new Integer[claims.length];
        for (int i = 0; i < claims.length; i++) {
            ticketIds[i] = claims[i].ticketId();
            generations[i] = claims[i].generation();
        }

        int renewed = jdbc.update(RENEW_SQL, ps -> {
            Connection connection = ps.getConnection();
            ps.setArray(1, connection.createArrayOf("int8", ticketIds));
            ps.setArray(2, connection.createArrayOf("int4", generations));
            ps.setString(3, WaitingTicket.WORKER_REF);
            ps.setDouble(4, WaitingTicket.LEASE.toSeconds());
        });
        log.debug("Renewed {} of {} tracked ticket leases", renewed, claims.length);
        return renewed;
    }

    private record Claim(long ticketId, int generation) {
    }
}
