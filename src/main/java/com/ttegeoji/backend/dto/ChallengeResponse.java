package com.ttegeoji.backend.dto;

import com.ttegeoji.backend.domain.Challenge;
import com.ttegeoji.backend.domain.enums.ChallengeStatus;
import com.ttegeoji.backend.util.Json;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public record ChallengeResponse(
        UUID id, UUID roomId, UUID userId, LocalDate weekStart, Object items, String narrative,
        Integer targetAmount, ChallengeStatus status, OffsetDateTime acceptedAt, OffsetDateTime resolvedAt,
        OffsetDateTime createdAt
) {
    public static ChallengeResponse from(Challenge c) {
        return new ChallengeResponse(
                c.getId(), c.getRoomId(), c.getUserId(), c.getWeekStart(), Json.read(c.getItems()), c.getNarrative(),
                c.getTargetAmount(), c.getStatus(), c.getAcceptedAt(), c.getResolvedAt(), c.getCreatedAt());
    }
}
