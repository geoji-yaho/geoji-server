package com.ttegeoji.backend.config;

import org.springframework.http.HttpStatus;

/**
 * 내부 API(/internal/v1/**) 거부. ApiExceptionHandler 가 {"code": code} 본문으로 바꾼다(10 §4.7).
 * code 는 10 에 이름이 있는 대문자 식별자만 쓴다(STALE_GENERATION, DEADLINE_EXCEEDED 등).
 */
public class InternalApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public InternalApiException(HttpStatus status, String code) {
        super(code);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
