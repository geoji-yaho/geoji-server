package com.ttegeoji.backend.internal.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/** 10 §4.1 CaseSnapshot.room_snapshots[]. intensity = rooms.spice_level, rule_version = rooms.rule_version(9/14 A안). */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record RoomSnapshot(
        @JsonProperty("room_id") String roomId,
        @JsonProperty("intensity") String intensity,
        @JsonProperty("rule_version") int ruleVersion) {
}
