package com.crs.backend.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 티켓 작업 실행기(구현계획 4절 (6) "claim한 티켓마다 작업 하나를 실행기에 넘긴다", Java 21 가상 스레드 작업당 1개).
 * 디스패처는 한 배치의 같은 과목 티켓을 작업 하나로 묶어 넘기고, 그 작업이 핸들러를 티켓 번호 순으로 하나씩 부른다.
 * <p>
 * 작업마다 가상 스레드를 하나 띄우고, 그 스레드가 허가를 얻은 뒤에만 본문을 실행한다. 작업 하나는 한 번에 핸들러 하나만
 * 돌리므로 동시에 도는 핸들러는 {@code queue.worker-concurrency}개 이하다. 핸들러는 트랜잭션 동안 DB 연결을 하나씩 쥐므로
 * 이 상한이 연결 풀보다 작아야 접수·claim·스케줄 작업이 연결을 얻는다. 허가는 작업 스레드가 기다리므로 제출하는 쪽은
 * 막히지 않는다. 세마포어는 공정 모드라 허가를 {@code acquire}에 도착한 순서대로 준다(제출 순서와는 다를 수 있다).
 * 나중에 도착한 작업이 먼저 기다린 작업을 계속 앞지르지 않게 하려는 것이며, 정확성은 이 순서에 기대지 않는다.
 * <p>
 * 제출했지만 끝나지 않은 작업(허가 대기 + 실행 중)을 세어 {@link #awaitIdle}이 그 수가 0이 될 때까지 기다린다.
 * 디스패처의 가변 상태는 모두 이 객체 안에 있어, {@code @MockitoSpyBean}이 디스패처 필드를 얕게 복사해도
 * 스파이와 원본이 같은 실행기를 공유한다.
 */
final class TicketWorkerPool {

    private static final Logger log = LoggerFactory.getLogger(TicketWorkerPool.class);

    private final ExecutorService threads = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("ticket-worker-", 0).factory()
    );
    private final Semaphore permits;
    private final ReentrantLock idleLock = new ReentrantLock();
    private final Condition idle = idleLock.newCondition();
    /** 제출했지만 끝나지 않은 작업 수(허가 대기 + 실행 중). {@link #idleLock}으로 보호한다 */
    private int inFlight;

    TicketWorkerPool(int concurrency) {
        if (concurrency < 1) {
            throw new IllegalArgumentException("queue.worker-concurrency must be at least 1: " + concurrency);
        }
        this.permits = new Semaphore(concurrency, true);
    }

    /**
     * {@code task}를 새 가상 스레드에 넘기고 곧바로 돌아온다. 그 스레드는 허가를 얻은 뒤 {@code task}를 실행한다.
     * <p>
     * {@code onFinish}는 어느 경우든 정확히 한 번, 허가를 반납하기 전에 실행된다. 작업이 끝났을 때, 종료가 시작된 뒤 허가를
     * 얻어 본문을 건너뛰었을 때, 허가를 기다리다 종료로 중단됐을 때, 그리고 종료 뒤라 거절됐을 때(이때는 호출 스레드에서)다.
     *
     * @return 넘겼으면 {@code true}, 종료 뒤라 거절했으면 {@code false}
     */
    boolean submit(Runnable task, Runnable onFinish) {
        started();
        boolean accepted = false;
        try {
            threads.execute(() -> run(task, onFinish));
            accepted = true;
        } catch (RejectedExecutionException e) {
            // 종료(shutdown) 뒤의 제출. 아래에서 정리하고 false를 돌려준다
        } finally {
            if (!accepted) {
                try {
                    onFinish.run();
                } finally {
                    finished();
                }
            }
        }
        return accepted;
    }

    /** 제출한 작업(허가 대기 + 실행 중)이 모두 끝나면 {@code true}, 제한 시간이 지나면 {@code false}. 시간 초과로 던지지 않는다 */
    boolean awaitIdle(Duration timeout) {
        long nanos = timeout.toNanos();
        idleLock.lock();
        try {
            while (inFlight > 0) {
                if (nanos <= 0) {
                    return false;
                }
                nanos = idle.awaitNanos(nanos);
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return inFlight == 0;
        } finally {
            idleLock.unlock();
        }
    }

    boolean isShutdown() {
        return threads.isShutdown();
    }

    /**
     * 새 작업을 받지 않고 남은 작업을 {@code grace}만큼 기다린다. 종료가 시작된 뒤 허가를 얻은 작업은 본문을 건너뛴다.
     * 그래도 남은 작업은 인터럽트해 허가 대기를 풀고 실행 중인 트랜잭션을 끊는다. 그 티켓은 PROCESSING으로 남아
     * 다음 기동의 회수(구현계획 4절 (8) 1)가 거둔다.
     */
    void shutdown(Duration grace) {
        threads.shutdown();
        try {
            if (threads.awaitTermination(grace.toNanos(), TimeUnit.NANOSECONDS)) {
                return;
            }
            log.warn("{} ticket workers did not finish within {}; interrupting them."
                    + " Their tickets stay PROCESSING for the startup orphan reclaim", inFlight(), grace);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        threads.shutdownNow();
    }

    private void run(Runnable task, Runnable onFinish) {
        boolean permitted = false;
        try {
            permits.acquire();
            permitted = true;
            if (!threads.isShutdown()) {
                task.run();
            }
        } catch (InterruptedException e) {
            // 종료 유예가 끝나 깨운 대기 작업. 본문 없이 정리만 한다
            Thread.currentThread().interrupt();
        } finally {
            try {
                onFinish.run();
            } finally {
                if (permitted) {
                    permits.release();
                }
                finished();
            }
        }
    }

    private void started() {
        idleLock.lock();
        try {
            inFlight++;
        } finally {
            idleLock.unlock();
        }
    }

    private void finished() {
        idleLock.lock();
        try {
            if (--inFlight == 0) {
                idle.signalAll();
            }
        } finally {
            idleLock.unlock();
        }
    }

    private int inFlight() {
        idleLock.lock();
        try {
            return inFlight;
        } finally {
            idleLock.unlock();
        }
    }
}
