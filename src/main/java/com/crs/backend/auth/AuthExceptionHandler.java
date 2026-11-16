package com.crs.backend.auth;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestControllerAdvice
public class AuthExceptionHandler {
    @ExceptionHandler(AuthFailure.class)
    public ResponseEntity<?> handle(AuthFailure failure) {
        return ResponseEntity.status(failure.status()).contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .header("Cache-Control", "no-store").body(Map.of("code", failure.code(), "status", failure.status()));
    }
}
