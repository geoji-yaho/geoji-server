package com.ttegeoji.backend.verdictview.dto;

import java.time.OffsetDateTime;

public record PostVoteResponse(String id, String postId, String roomId, String verdict, String reason,
                               OffsetDateTime createdAt) {
}
