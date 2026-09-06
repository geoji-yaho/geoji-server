package com.ttegeoji.backend.dto;

import com.ttegeoji.backend.domain.CrownHistory;

import java.time.OffsetDateTime;
import java.util.UUID;

public record CrownHistoryResponse(
        UUID id, UUID roomId, UUID userId, OffsetDateTime startedAt, OffsetDateTime endedAt, UUID dethronedByExpenseId
) {
    public static CrownHistoryResponse from(CrownHistory c) {
        return new CrownHistoryResponse(
                c.getId(), c.getRoomId(), c.getUserId(), c.getStartedAt(), c.getEndedAt(), c.getDethronedByExpenseId());
    }
}
