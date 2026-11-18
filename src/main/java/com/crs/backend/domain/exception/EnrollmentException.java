package com.crs.backend.domain.exception;

import lombok.Getter;

@Getter
public class EnrollmentException extends RuntimeException {

    private final ErrorCode errorCode;
    private final Long activeTicketId;

    public EnrollmentException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
        this.activeTicketId = null;
    }

    public EnrollmentException(ErrorCode errorCode, String detail) {
        super(detail);
        this.errorCode = errorCode;
        this.activeTicketId = null;
    }

    public EnrollmentException(ErrorCode errorCode, Long activeTicketId) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
        this.activeTicketId = activeTicketId;
    }
}
