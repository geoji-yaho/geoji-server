package com.ttegeoji.backend.internal.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/** 10 §4.2 recent_verdicts[]. verdict_id 는 워커 모델이 알 수 없는 필드로 거부하므로 넣지 않는다(9/14 결정). */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record RecentVerdict(
        @JsonProperty("post_id") String postId,
        @JsonProperty("post_version") int postVersion,
        @JsonProperty("category") String category,
        @JsonProperty("amount_krw") int amountKrw,
        @JsonProperty("reason") String reason,
        @JsonProperty("result") String result,
        @JsonProperty("sentence") String sentence,
        @JsonProperty("judged_at") String judgedAt,
        @JsonProperty("scope") EvidenceScope scope) {
}
