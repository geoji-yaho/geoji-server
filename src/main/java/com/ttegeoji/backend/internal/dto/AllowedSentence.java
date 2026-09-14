package com.ttegeoji.backend.internal.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/** 10 §4.1 jury.policy.allowed_sentences[]. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AllowedSentence(
        @JsonProperty("code") String code,
        @JsonProperty("rank") int rank) {
}
