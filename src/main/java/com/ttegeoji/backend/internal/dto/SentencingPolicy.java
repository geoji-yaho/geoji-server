package com.ttegeoji.backend.internal.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** 10 §4.1 jury.policy. verdicts.policy_snapshot jsonb 를 이 모양으로 읽어 그대로 싣는다. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record SentencingPolicy(
        @JsonProperty("version") String version,
        @JsonProperty("allowed_sentences") List<AllowedSentence> allowedSentences,
        @JsonProperty("fallback_sentence") String fallbackSentence,
        @JsonProperty("reason_required") boolean reasonRequired) {
}
