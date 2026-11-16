package com.project1.enrollment.queue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 1초마다 waiting_queue 상위 50명을 active_users로 이동시킨다.
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
            log.debug("Admitted {} users from waiting_queue to active_users", admitted);
        }
    }
}
