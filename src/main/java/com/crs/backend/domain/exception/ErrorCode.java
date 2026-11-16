package com.crs.backend.domain.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {
    E01(HttpStatus.UNAUTHORIZED, "인증 불일치 또는 자격 증명 만료"),
    E02(HttpStatus.FORBIDDEN, "인증·권한·소유권 없음"),
    E03(HttpStatus.CONFLICT, "미완료 티켓이 이미 존재함"),
    E04(HttpStatus.NOT_FOUND, "과목을 찾을 수 없음"),
    E05(HttpStatus.CONFLICT, "폐강 과목"),
    E06(HttpStatus.CONFLICT, "이미 신청한 과목"),
    E07(HttpStatus.CONFLICT, "여석 없음"),
    E08(HttpStatus.CONFLICT, "본인 현재 신청 내역 없음"),
    E09(HttpStatus.BAD_REQUEST, "입력값 오류"),
    E10(HttpStatus.CONFLICT, "과목코드 중복"),
    E11(HttpStatus.CONFLICT, "정원이 현재 인원보다 작음"),
    E13(HttpStatus.UNPROCESSABLE_ENTITY, "학생 계정이 아님"),
    E14(HttpStatus.CONFLICT, "폐강 대상 아님"),
    E15(HttpStatus.CONFLICT, "수강신청 화면 미입장"),
    E99(HttpStatus.CONFLICT, "재투입 한도 소진"),
    CREDIT_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "최대 신청 가능 학점 초과"),
    LOCK_NOT_ACQUIRED(HttpStatus.TOO_MANY_REQUESTS, "분산 락 획득 실패");

    private final HttpStatus httpStatus;
    private final String message;
}
