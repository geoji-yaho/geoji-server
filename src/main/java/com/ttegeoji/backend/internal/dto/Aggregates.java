package com.ttegeoji.backend.internal.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/** 10 §4.2 aggregates. burn_rate 는 0~1 비율(§14 답). */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record Aggregates(
        @JsonProperty("burn_rate") double burnRate,
        @JsonProperty("tier") String tier,
        @JsonProperty("no_spend_days") int noSpendDays,
        @JsonProperty("repeat_same_category_30d") int repeatSameCategory30d,
        @JsonProperty("excludes_post_id") String excludesPostId,
        @JsonProperty("window") AggregateWindow window,
        @JsonProperty("rule_version") int ruleVersion) {
}
