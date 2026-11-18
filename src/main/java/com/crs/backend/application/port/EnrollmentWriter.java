package com.crs.backend.application.port;

import com.crs.backend.domain.enums.TicketResultCode;

/**
 * 수강신청 내역 쓰기 공용 경로 (BE-08).
 * HTTP 예외가 아니라 티켓 결과 코드로 실패를 표현한다.
 */
public interface EnrollmentWriter {

    TicketResultCode enrollStudent(String studentId, String courseInstId, Long sourceTicketId);

    TicketResultCode cancelStudent(String studentId, String courseInstId, Long sourceTicketId);
}
