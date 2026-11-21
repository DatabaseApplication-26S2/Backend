package com.crs.backend.application;

import com.crs.backend.adapter.out.persistence.jpa.ServiceSettingsRepository;
import com.crs.backend.adapter.out.persistence.jpa.WaitingTicketJpaRepository;
import com.crs.backend.application.port.TicketHandler;
import com.crs.backend.domain.entity.ServiceSettings;
import com.crs.backend.domain.entity.WaitingTicket;
import com.crs.backend.domain.enums.TicketRequestType;
import com.crs.backend.domain.enums.TicketStatus;
import com.crs.backend.queue.QueueProperties;
import com.crs.backend.queue.RedisWaitingOrderCopy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * QUE-02-01 DB claim (N′ = max(N − PROCESSING, 0)) 후 claim한 티켓을 워커 실행기에 넘긴다(구현계획 4절 (6)).
 * <p>
 * claim 트랜잭션은 호출 스레드에서 동기로 커밋하고, 핸들러는 기다리지 않는다. 한 배치의 티켓은 대상 과목별로 묶어 묶음마다
 * 작업 하나(가상 스레드 하나)를 넘긴다. 같은 과목 티켓은 그 작업 안에서 티켓 번호 순으로 차례로 돈다. 그래서 같은 배치의
 * 앞 번호가 처리 중이라는 이유로 뒤 번호가 WAITING으로 되돌아가 다음 claim 주기를 기다리지 않는다.
 * 과목이 다른 묶음과 과목이 없는 티켓(LOGIN·ADMISSION, 하나씩 따로 묶는다)은 {@code queue.worker-concurrency}개까지
 * 동시에 돈다({@link TicketWorkerPool}). 다른 배치의 같은 과목 앞 번호가 미완료이면 핸들러가 자기 티켓을 WAITING으로
 * 돌린다(4절 (4) 2).
 * <p>
 * claim한 작업은 끝날 때까지 {@link TicketLeaseRenewer}가 리스를 연장한다(4절 (8)). 워커 허가를 기다리는 묶음과
 * 묶음 안에서 앞 티켓을 기다리는 작업도 대상이다.
 */
@Service
public class TicketDispatcher implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(TicketDispatcher.class);

    /** 종료 때 남은 작업을 기다리는 최대 시간. 그 뒤에도 PROCESSING인 티켓은 다음 기동의 회수(4절 (8) 1)가 거둔다 */
    private static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(10);

    private final ServiceSettingsRepository serviceSettingsRepository;
    private final WaitingTicketJpaRepository waitingTicketRepository;
    private final JdbcTemplate jdbc;
    private final RedisWaitingOrderCopy waitingOrderCopy;
    private final Map<TicketRequestType, TicketHandler> handlers;
    private final TicketLeaseRenewer leaseRenewer;
    private final TransactionTemplate claimTx;
    private final TicketWorkerPool workers;

    public TicketDispatcher(
            ServiceSettingsRepository serviceSettingsRepository,
            WaitingTicketJpaRepository waitingTicketRepository,
            JdbcTemplate jdbc,
            RedisWaitingOrderCopy waitingOrderCopy,
            List<TicketHandler> handlerList,
            TicketLeaseRenewer leaseRenewer,
            QueueProperties queueProperties,
            PlatformTransactionManager transactionManager
    ) {
        this.serviceSettingsRepository = serviceSettingsRepository;
        this.waitingTicketRepository = waitingTicketRepository;
        this.jdbc = jdbc;
        this.waitingOrderCopy = waitingOrderCopy;
        this.handlers = new EnumMap<>(TicketRequestType.class);
        for (TicketHandler handler : handlerList) {
            this.handlers.put(handler.supports(), handler);
        }
        this.leaseRenewer = leaseRenewer;
        this.claimTx = new TransactionTemplate(transactionManager);
        this.workers = new TicketWorkerPool(queueProperties.getWorkerConcurrency());
    }

    /**
     * claim 트랜잭션을 커밋한 뒤 claim한 작업을 모두 리스 연장 대상으로 올리고, 과목별 묶음마다 작업 하나를 워커 실행기에 넘긴다.
     * 핸들러를 기다리지 않고 claim한 티켓 수를 돌려준다. 핸들러가 끝나기를 기다려야 하면 {@link #awaitIdle}을 부른다.
     */
    public int claimAndDispatch() {
        if (workers.isShutdown()) {
            return 0; // 종료 중에는 실행할 워커가 없으므로 티켓을 PROCESSING으로 만들지 않는다
        }
        List<ClaimedWork> works = claimTx.execute(status -> claimBatch());
        if (works == null || works.isEmpty()) {
            return 0;
        }
        // 작업을 넘기기 전에 배치 전체를 리스 연장 대상으로 올린다(4절 (8)). 워커 허가를 기다리거나 묶음 안에서 앞 티켓을
        // 기다리는 동안 리스가 끝나 회수되지 않게 한다. 연장은 티켓마다 처리가 끝나면(실패·건너뜀·거절 포함) 멈춘다.
        // 실패로 PROCESSING에 남은 티켓은 리스가 끝나면 회수기가 거둔다
        for (ClaimedWork work : works) {
            leaseRenewer.track(work.ticketId(), work.generation());
        }
        List<CourseRun> runs = groupByCourse(works);
        int next = 0;
        try {
            while (next < runs.size()) {
                CourseRun run = runs.get(next++);
                if (!workers.submit(run::runInOrder, run::untrackRemaining)) {
                    log.warn("Ticket workers are shut down; {} claimed tickets from ticketId={} stay PROCESSING for reclaim",
                            run.works.size(), run.works.getFirst().ticketId());
                }
            }
        } finally {
            // Error로 제출이 중간에 끝나면 넘기지 못한 묶음도 내려놓는다. 그래야 끝없이 연장되지 않고 리스 만료 뒤 회수된다
            while (next < runs.size()) {
                runs.get(next++).untrackRemaining();
            }
        }
        return works.size();
    }

    /**
     * 넘긴 묶음 작업(허가 대기 + 실행 중)이 모두 끝날 때까지 기다린다. 끝나면 {@code true}, 제한 시간이 지나면 {@code false}를
     * 돌려주며 시간 초과로 던지지 않는다. {@link #claimAndDispatch} 뒤 핸들러 결과를 읽어야 하는 호출자가 부른다.
     */
    public boolean awaitIdle(Duration timeout) {
        return workers.awaitIdle(timeout);
    }

    /**
     * 새 작업을 받지 않고 남은 작업을 최대 10초 기다린다. 아직 허가를 얻지 못한 묶음은 시작하지 않고, 실행 중인 묶음은
     * 지금 티켓까지만 처리한다. 그 뒤에도 PROCESSING인 티켓은 다음 기동의 회수(4절 (8) 1)에 맡긴다.
     */
    @Override
    public void destroy() {
        workers.shutdown(SHUTDOWN_GRACE);
    }

    /**
     * 같은 과목 티켓을 한 묶음으로 모으고, 과목이 없는 티켓(LOGIN·ADMISSION)은 하나씩 따로 묶는다.
     * 묶음 안은 티켓 번호 오름차순이고, 묶음은 첫 티켓 번호 순이다(4절 (4) 2·(6)).
     */
    private List<CourseRun> groupByCourse(List<ClaimedWork> works) {
        List<ClaimedWork> ordered = new ArrayList<>(works);
        ordered.sort(Comparator.comparingLong(ClaimedWork::ticketId));
        List<CourseRun> runs = new ArrayList<>();
        Map<String, CourseRun> byCourse = new HashMap<>();
        for (ClaimedWork work : ordered) {
            String course = work.targetCourseInstId();
            CourseRun run = course == null ? null : byCourse.get(course);
            if (run == null) {
                run = new CourseRun();
                runs.add(run);
                if (course != null) {
                    byCourse.put(course, run);
                }
            }
            run.works.add(work);
        }
        return runs;
    }

    /** 티켓 하나의 처리(워커 스레드, 묶음의 허가를 쥔 상태). 예외는 기록하고 삼켜 묶음의 다음 티켓으로 넘어간다 */
    private void dispatch(ClaimedWork work) {
        try {
            TicketHandler handler = handlers.get(work.requestType());
            if (handler == null) {
                log.warn("No TicketHandler for {}", work.requestType());
                return;
            }
            WaitingTicket ticket = waitingTicketRepository.findById(work.ticketId()).orElse(null);
            if (ticket != null) {
                handler.handle(ticket, work.generation());
            }
        } catch (Exception e) {
            log.error("Ticket handler failed ticketId={}", work.ticketId(), e);
        }
    }

    /**
     * 한 과목(또는 과목 없는 티켓 하나)의 묶음. 워커 작업 하나가 허가 하나로 티켓 번호 순으로 차례로 처리한다.
     * {@link #next}는 그 작업 스레드만 쓴다. 제출이 거절되면 본문은 돌지 않고 제출 스레드가 {@link #untrackRemaining}만 부른다.
     */
    private final class CourseRun {

        private final List<ClaimedWork> works = new ArrayList<>();
        private int next;

        /** 차례로 처리하고 티켓마다 연장을 멈춘다. 종료가 시작됐거나 인터럽트되면 남은 티켓을 시작하지 않는다 */
        void runInOrder() {
            while (next < works.size() && !workers.isShutdown() && !Thread.currentThread().isInterrupted()) {
                ClaimedWork work = works.get(next++);
                try {
                    dispatch(work);
                } finally {
                    leaseRenewer.untrack(work.ticketId(), work.generation());
                }
            }
        }

        /** 아직 처리하지 않은 티켓의 연장을 멈춘다. 작업이 끝날 때(Error·인터럽트·종료·거절 포함) 워커 실행기가 부른다 */
        void untrackRemaining() {
            while (next < works.size()) {
                ClaimedWork rest = works.get(next++);
                leaseRenewer.untrack(rest.ticketId(), rest.generation());
            }
        }
    }

    private List<ClaimedWork> claimBatch() {
        ServiceSettings settings = serviceSettingsRepository
                .findByIdForUpdate(ServiceSettings.SINGLETON_ID)
                .orElseGet(() -> serviceSettingsRepository.save(ServiceSettings.initial()));

        long processing = waitingTicketRepository.countByStatus(TicketStatus.PROCESSING);
        int nPrime = Math.max(settings.getConcurrencyLimit() - (int) processing, 0);
        if (nPrime <= 0) {
            return List.of();
        }

        List<WaitingTicket> claimed = waitingTicketRepository.findByStatusForClaim(
                TicketStatus.WAITING, PageRequest.of(0, nPrime)
        );
        if (claimed.isEmpty()) {
            return List.of();
        }

        // 배정 기준 시각 :claimed_at(구현계획 4절 (3)). 회계 잠금을 얻은 뒤 DB 시계에서 한 번 읽어 이 배치 전체에 쓴다.
        // 배정할 WAITING 행을 잠근 뒤에 읽으므로 그 티켓들의 접수 시각보다 늦다.
        Instant claimedAt = jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();

        for (WaitingTicket ticket : claimed) {
            // 세대 +1, 슬롯 = 세대, 소유자 = WaitingTicket.WORKER_REF, 리스 = claimedAt + WaitingTicket.LEASE (tickets CHECK 3)
            ticket.markProcessing(claimedAt);
            waitingTicketRepository.save(ticket);
            waitingOrderCopy.removeAfterCommit(ticket.getTicketId());
        }

        return claimed.stream()
                .map(t -> new ClaimedWork(
                        t.getTicketId(), t.getGenerationNo(), t.getRequestType(), t.getTargetCourseInstId()))
                .toList();
    }

    private record ClaimedWork(
            long ticketId, int generation, TicketRequestType requestType, String targetCourseInstId) {
    }
}
