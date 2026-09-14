package com.ttegeoji.backend.internal.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/** 10 §4.1 CaseSnapshot.jury. guilty_ratio 는 0..1 소수(9/11), 백분율이 아니다. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record JurySnapshot(
        @JsonProperty("verdict_id") String verdictId,
        @JsonProperty("verdict_version") int verdictVersion,
        @JsonProperty("result") String result,
        @JsonProperty("vote_counts") Map<String, Integer> voteCounts,
        @JsonProperty("guilty_ratio") double guiltyRatio,
        @JsonProperty("confirmed_at") String confirmedAt,
        @JsonProperty("deadline_at") String deadlineAt,
        @JsonProperty("policy") SentencingPolicy policy,
        @JsonProperty("target_intensities") List<String> targetIntensities,
        @JsonProperty("default_intensity") String defaultIntensity) {
}
