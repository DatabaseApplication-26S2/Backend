package com.crs.backend.application.port;

import com.crs.backend.adapter.in.web.dto.TicketReceipt;

/**
 * 대기 티켓 접수 내부 계약 (QUE-01-01 / BE-03).
 * LOGIN 접수는 기존 AuthService가 담당하고, 입장·신청·취소만 이 경로를 쓴다.
 */
public interface TicketIntake {

    TicketReceipt intakeAdmission(String userId, String sessionId);

    TicketReceipt intakeEnrollment(String userId, String sessionId, String courseInstId, String courseCode);

    TicketReceipt intakeCancellation(String userId, String sessionId, String courseInstId, String courseCode);
}
