package com.crs.backend.queue;

/** 현재 세션이 수강신청 화면에 입장하지 않았다(DB sessions.entered_at 없음). 409 E15로 응답한다. */
public class QueueNotAllowedException extends RuntimeException {

    public QueueNotAllowedException(String userId) {
        super("Session has not been admitted: " + userId);
    }
}
