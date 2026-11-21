package com.crs.backend.queue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 디스패처 트리거. 1초마다 TicketDispatcher.claimAndDispatch()를 불러
 * DB의 WAITING 티켓을 N′ = max(N − PROCESSING, 0)건까지 claim하고, 티켓마다 유형별 핸들러 작업을 워커 실행기에 넘긴다.
 */
@Component
public class QueueAdmissionScheduler {

    private static final Logger log = LoggerFactory.getLogger(QueueAdmissionScheduler.class);

    private final QueueService queueService;

    public QueueAdmissionScheduler(QueueService queueService) {
        this.queueService = queueService;
    }

    @Scheduled(fixedDelayString = "${queue.scheduler-fixed-delay-ms:1000}")
    public void admitWaitingUsers() {
        int admitted = queueService.admitWaitingUsers();
        if (admitted > 0) {
            log.debug("Claimed and dispatched {} tickets", admitted);
        }
    }
}
