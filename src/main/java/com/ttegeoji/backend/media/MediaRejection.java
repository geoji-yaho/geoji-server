package com.ttegeoji.backend.media;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/** 업로드 거부. 컨트롤러가 {@code {"message": ...}} 로 바꾼다(공개 API 규약) */
@Getter
public class MediaRejection extends RuntimeException {

    private final HttpStatus status;

    public MediaRejection(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }
}
