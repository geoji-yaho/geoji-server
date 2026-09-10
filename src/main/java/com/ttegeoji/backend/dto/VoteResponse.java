package com.ttegeoji.backend.dto;

import com.ttegeoji.backend.domain.ExpenseVote;
import com.ttegeoji.backend.domain.enums.VerdictType;

import java.time.OffsetDateTime;
import java.util.UUID;

public record VoteResponse(
        UUID id, UUID voterUserId, VerdictType verdict, String reason, OffsetDateTime createdAt
) {
    public static VoteResponse from(ExpenseVote v) {
        return new VoteResponse(v.getId(), v.getVoterUserId(), v.getVerdict(), v.getReason(), v.getCreatedAt());
    }
}
