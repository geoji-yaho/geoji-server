package com.ttegeoji.backend.internal.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/** 10 §4.1 RETAIN comment.approved 확장. content 가 1000자를 넘으면 워커가 스냅샷 전체를 거부한다. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record CommentSnapshot(
        @JsonProperty("comment_id") String commentId,
        @JsonProperty("version") int version,
        @JsonProperty("room_id") String roomId,
        @JsonProperty("post_id") String postId,
        @JsonProperty("post_status") String postStatus,
        @JsonProperty("author_id") String authorId,
        @JsonProperty("content") String content,
        @JsonProperty("created_at") String createdAt) {
}
