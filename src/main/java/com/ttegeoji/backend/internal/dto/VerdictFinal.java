package com.ttegeoji.backend.internal.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/** 10 §4.1 RETAIN sentence.finalized 확장. 여섯 키 모두 required 라 null 이어도 키를 싣는다. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record VerdictFinal(
        @JsonProperty("sentence") String sentence,
        @JsonProperty("sentence_source") String sentenceSource,
        @JsonProperty("sentencing_reason") String sentencingReason,
        @JsonProperty("reason_source") String reasonSource,
        @JsonProperty("applied_intensity") String appliedIntensity,
        @JsonProperty("banter_strategy") String banterStrategy) {
}
