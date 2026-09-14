package com.ttegeoji.backend.internal.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** 10 §4.2 scope{visibility, room_ids}. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record EvidenceScope(
        @JsonProperty("visibility") Visibility visibility,
        @JsonProperty("room_ids") List<String> roomIds) {

    public enum Visibility {
        PUBLIC, ROOMS, PRIVATE
    }
}
