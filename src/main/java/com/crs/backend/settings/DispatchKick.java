package com.crs.backend.settings;

import com.crs.backend.application.TicketDispatcher;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * N 변경 직후 디스패처를 한 번 깨운다(ADM-02-01 "이후 새 claim부터 적용").
 *
 * 1초 스케줄(QueueAdmissionScheduler)을 기다리지 않고 새 N으로 곧바로 claim하게 한다.
 * 요청 스레드에서 직접 부르면 claim한 티켓의 핸들러까지 응답 전에 실행되므로 별도 스레드 1개에서 실행한다.
 * 이미 예약된 실행이 있으면 합친다. claim은 회계 잠금(service_settings 행)으로 직렬화되므로
 * 스케줄러와 겹쳐 실행돼도 N′ = max(N − PROCESSING, 0)을 넘지 않는다.
 */
@Component
public class DispatchKick implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(DispatchKick.class);

    private final TicketDispatcher dispatcher;
    private final AtomicBoolean pending = new AtomicBoolean();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "settings-dispatch-kick");
        t.setDaemon(true);
        return t;
    });

    public DispatchKick(@Lazy TicketDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    /** 커밋이 끝난 뒤에 부른다 */
    public void kick() {
        if (!pending.compareAndSet(false, true)) {
            return; // 아직 시작하지 않은 실행이 있으면 그 실행이 새 N을 읽는다
        }
        executor.execute(() -> {
            pending.set(false);
            try {
                int claimed = dispatcher.claimAndDispatch();
                log.debug("Dispatch kicked after settings change: claimed {}", claimed);
            } catch (RuntimeException e) {
                log.warn("Dispatch kick failed; the scheduler will retry", e);
            }
        });
    }

    @Override
    public void destroy() {
        executor.shutdownNow();
    }
}
