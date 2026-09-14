package com.ttegeoji.backend.internal.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/** 10 §4.2 응답 sources[]. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record EvidenceSource(
        @JsonProperty("source_type") String sourceType,
        @JsonProperty("source_id") String sourceId,
        @JsonProperty("source_version") long sourceVersion,
        @JsonProperty("payload") Map<String, Object> payload,
        @JsonProperty("scope") EvidenceScope scope) {
}
