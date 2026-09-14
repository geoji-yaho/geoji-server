package com.ttegeoji.backend.internal.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** 10 §4.2 요청 candidates[]. 네 키 모두 필수·non-null. 알 수 없는 키는 ResolveEvidenceRequest.parse 가 거부한다. */
@JsonIgnoreProperties(ignoreUnknown = false)
public record EvidenceCandidate(
        @JsonProperty(value = "source_type", required = true) String sourceType,
        @JsonProperty(value = "source_id", required = true) String sourceId,
        @JsonProperty(value = "source_version", required = true) long sourceVersion,
        @JsonProperty(value = "score", required = true) double score) {
}
