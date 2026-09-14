package com.ttegeoji.backend.verdictview;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 공개 재판 API 거부. 컨트롤러가 {"message"} 본문으로 바꾼다(TraceProxyController 관례).
 * 트랜잭션 안에서 던지면 롤백된다.
 */
@Getter
public class PublicApiRejection extends RuntimeException {

    private final HttpStatus status;

    public PublicApiRejection(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public static PublicApiRejection notFound() {
        return new PublicApiRejection(HttpStatus.NOT_FOUND, "게시물을 찾을 수 없습니다.");
    }
}
