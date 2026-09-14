package com.ttegeoji.backend.internal.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** 10 §4.2 응답. 다섯 키 모두 필수. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ResolveEvidenceResponse(
        @JsonProperty("sources") List<EvidenceSource> sources,
        @JsonProperty("aggregates") Aggregates aggregates,
        @JsonProperty("room_rules") List<RoomRule> roomRules,
        @JsonProperty("recent_verdicts") List<RecentVerdict> recentVerdicts,
        @JsonProperty("style_comments") List<StyleComment> styleComments) {
}
