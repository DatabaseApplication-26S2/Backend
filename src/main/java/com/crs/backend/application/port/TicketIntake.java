package com.crs.backend.application.port;

import com.crs.backend.adapter.in.web.dto.TicketReceipt;

/**
 * 대기 티켓 접수 내부 계약 (QUE-01-01 / BE-03).
 * 신청·취소 접수만 이 경로를 쓴다. 입장·LOGIN 접수는 AuthService가 담당한다.
 * 모든 접수는 같은 접수 잠금(AuthService.INTAKE_LOCK)을 잡은 뒤 미완료를 조회하고 저장한다(구현계획 4절 (1)·(2)).
 */
public interface TicketIntake {

    TicketReceipt intakeEnrollment(String userId, String sessionId, String courseInstId, String courseCode);

    TicketReceipt intakeCancellation(String userId, String sessionId, String courseInstId, String courseCode);
}
