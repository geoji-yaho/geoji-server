package com.ttegeoji.backend.dto;

import com.ttegeoji.backend.domain.Comment;

import java.time.OffsetDateTime;
import java.util.UUID;

public record CommentResponse(
        UUID id, UUID expenseId, UUID roomId, UUID userId, String content, OffsetDateTime createdAt
) {
    public static CommentResponse from(Comment c) {
        return new CommentResponse(c.getId(), c.getExpenseId(), c.getRoomId(), c.getUserId(), c.getContent(), c.getCreatedAt());
    }
}
