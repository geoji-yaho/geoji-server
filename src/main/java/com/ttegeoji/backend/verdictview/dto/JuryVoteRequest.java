package com.ttegeoji.backend.verdictview.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.ttegeoji.backend.config.InternalApiException;
import org.springframework.http.HttpStatus;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.Set;
import java.util.UUID;

/**
 * 19 §5 요청 본문. 워커 모델(AI 저장소 ports/backend.py JuryVoteRequest, 7키)과 같은 규칙으로 받는다.
 * 알 수 없는 키·빠진 키·UUID 아님은 422 INVALID_REQUEST. ResolveEvidenceRequest 와 같이 전용 엄격 매퍼로 파싱한다.
 * verdict·reason 의 뜻 검사(글 유형·길이)는 19 §5 순서상 6단계라 서비스가 한다.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record JuryVoteRequest(
        @JsonProperty(value = "job_id", required = true) UUID jobId,
        @JsonProperty(value = "generation_id", required = true) UUID generationId,
        @JsonProperty(value = "room_id", required = true) UUID roomId,
        @JsonProperty(value = "voter_id", required = true) UUID voterId,
        @JsonProperty(value = "verdict", required = true) String verdict,
        @JsonProperty(value = "reason", required = true) String reason,
        @JsonProperty(value = "source", required = true) String source) {

    public static final Set<String> SOURCES = Set.of("AI", "TEMPLATE");

    private static final JsonMapper STRICT = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    /** 본문 스키마 위반이면 422 INVALID_REQUEST. 예외 메시지·입력값은 본문과 로그에 싣지 않는다(10 §4.7). */
    public static JuryVoteRequest parse(byte[] body) {
        if (body == null || body.length == 0) {
            throw invalid();
        }
        JuryVoteRequest request;
        try {
            request = STRICT.readValue(body, JuryVoteRequest.class);
        } catch (JacksonException e) {
            throw invalid();
        }
        if (request == null || !SOURCES.contains(request.source())) {
            throw invalid();
        }
        return request;
    }

    private static InternalApiException invalid() {
        return new InternalApiException(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_REQUEST");
    }
}
