package com.ttegeoji.backend.comment.dto;

import java.time.OffsetDateTime;

public record PostCommentResponse(String id, String postId, String roomId, String userId, String nickname,
                                  String content, OffsetDateTime createdAt) {
}
