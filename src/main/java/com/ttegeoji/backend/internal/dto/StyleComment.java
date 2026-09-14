package com.ttegeoji.backend.internal.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/** 10 §4.2 style_comments[]. P0 는 늘 빈 배열(§15.3 D-04). */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record StyleComment(
        @JsonProperty("comment_id") String commentId,
        @JsonProperty("room_id") String roomId,
        @JsonProperty("content") String content,
        @JsonProperty("created_at") String createdAt) {
}
