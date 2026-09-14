package com.ttegeoji.backend.internal.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** 10 §4.1 CaseSnapshot.audience. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record Audience(
        @JsonProperty("room_ids") List<String> roomIds,
        @JsonProperty("audience_version") int audienceVersion,
        @JsonProperty("public_share_enabled") boolean publicShareEnabled) {
}
