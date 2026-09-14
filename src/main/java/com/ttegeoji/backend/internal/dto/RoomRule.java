package com.ttegeoji.backend.internal.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/** 10 §4.2 room_rules[]. rule_id = rooms.rules 배열 인덱스(0부터, 문자열), version = rooms.rule_version(9/14 A안). */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record RoomRule(
        @JsonProperty("room_id") String roomId,
        @JsonProperty("rule_id") String ruleId,
        @JsonProperty("version") int version,
        @JsonProperty("text") String text) {
}
