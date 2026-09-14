package com.ttegeoji.backend.internal.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.ttegeoji.backend.config.InternalApiException;
import org.springframework.http.HttpStatus;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

/**
 * 10 §4.2 요청. 워커 모델(extra="forbid", candidates max_length=20)과 같은 규칙으로 받는다.
 * Jackson 3 는 알 수 없는 필드를 기본으로 무시하고 @JsonIgnoreProperties(ignoreUnknown=false) 만으로는 실패하지 않는다.
 * 전역 설정(application.yml)에 기대지 않으려고 이 DTO 전용 엄격 매퍼로 직접 파싱한다.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record ResolveEvidenceRequest(
        @JsonProperty(value = "candidates", required = true) List<EvidenceCandidate> candidates,
        @JsonProperty(value = "include", required = true) List<EvidenceInclude> include) {

    public static final int MAX_CANDIDATES = 20;

    private static final JsonMapper STRICT = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .build();

    /** 본문 스키마 위반이면 422 INVALID_REQUEST. 예외 메시지·입력값은 본문과 로그에 싣지 않는다(10 §4.7). */
    public static ResolveEvidenceRequest parse(byte[] body) {
        if (body == null || body.length == 0) {
            throw invalid();
        }
        ResolveEvidenceRequest request;
        try {
            request = STRICT.readValue(body, ResolveEvidenceRequest.class);
        } catch (JacksonException e) {
            throw invalid();
        }
        if (request == null
                || request.candidates().size() > MAX_CANDIDATES
                || request.candidates().contains(null)
                || request.include().contains(null)) {
            throw invalid();
        }
        return request;
    }

    private static InternalApiException invalid() {
        return new InternalApiException(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_REQUEST");
    }
}
