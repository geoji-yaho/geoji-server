package com.ttegeoji.backend.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record RoomMemberResponse(
        UUID userId, String nickname, String avatarUrl, BigDecimal debtScore, OffsetDateTime joinedAt
) {
}
