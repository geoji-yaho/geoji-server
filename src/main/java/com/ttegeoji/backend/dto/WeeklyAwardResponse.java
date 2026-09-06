package com.ttegeoji.backend.dto;

import com.ttegeoji.backend.domain.WeeklyAward;
import com.ttegeoji.backend.domain.enums.AwardType;
import com.ttegeoji.backend.util.Json;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public record WeeklyAwardResponse(
        UUID id, UUID roomId, LocalDate weekStart, LocalDate weekEnd, AwardType awardType,
        String title, UUID winnerUserId, String description, Object statsSnapshot, OffsetDateTime createdAt
) {
    public static WeeklyAwardResponse from(WeeklyAward a) {
        return new WeeklyAwardResponse(
                a.getId(), a.getRoomId(), a.getWeekStart(), a.getWeekEnd(), a.getAwardType(),
                a.getTitle(), a.getWinnerUserId(), a.getDescription(), Json.read(a.getStatsSnapshot()), a.getCreatedAt());
    }
}
