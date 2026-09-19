package com.ttegeoji.backend.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final String INTERNAL_PREFIX = "/internal/v1/";

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleConflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", e.getMessage()));
    }

    /**
     * 업로드가 Spring 상한을 넘었다. 여기까지 오면 바이트를 읽기 전이라 서비스의 8MiB 검사가
     * 돌지 못한다. 같은 뜻의 413 을 같은 본문 모양으로 돌려준다(10 §16.5).
     * 앞단 nginx 상한(.platform/nginx/conf.d/upload.conf)에 걸리면 이 핸들러도 못 탄다.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, String>> handleUploadTooLarge(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE)
                .body(Map.of("message", "이미지는 8MiB 이하여야 합니다."));
    }

    // 유니크 제약(예: rooms.invite_code, weekly_awards의 room+week+type+title 등) 위반
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, String>> handleDataIntegrityViolation(DataIntegrityViolationException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", "이미 존재하는 값이거나 제약 조건을 위반했습니다."));
    }

    // 내부 API 거부 본문은 {"code"} 하나다(10 §4.7). 공개 API 의 {"message"} 와 섞지 않는다.
    @ExceptionHandler(InternalApiException.class)
    public ResponseEntity<Map<String, String>> handleInternalApi(InternalApiException e) {
        return ResponseEntity.status(e.getStatus()).body(Map.of("code", e.getCode()));
    }

    // 내부 API 본문 파싱·검증 실패 → 422 INVALID_REQUEST. 검증 오류 원문·입력값은 본문에 넣지 않는다(10 §4.7).
    // 공개 API 는 이전처럼 Spring 기본 처리로 넘긴다. 같은 예외를 다시 던지면 다음 resolver 가 처리한다.
    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentNotValidException.class,
            HandlerMethodValidationException.class
    })
    public ResponseEntity<Map<String, String>> handleInvalidRequest(Exception e, HttpServletRequest request)
            throws Exception {
        if (!isInternal(request)) {
            throw e;
        }
        return ResponseEntity.unprocessableContent().body(Map.of("code", "INVALID_REQUEST"));
    }

    private static boolean isInternal(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return path.startsWith(INTERNAL_PREFIX);
    }
}
