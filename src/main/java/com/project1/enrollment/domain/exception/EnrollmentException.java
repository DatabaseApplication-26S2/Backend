package com.project1.enrollment.domain.exception;

import lombok.Getter;

@Getter
public class EnrollmentException extends RuntimeException {

    private final ErrorCode errorCode;

    public EnrollmentException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public EnrollmentException(ErrorCode errorCode, String detail) {
        super(detail);
        this.errorCode = errorCode;
    }
}
