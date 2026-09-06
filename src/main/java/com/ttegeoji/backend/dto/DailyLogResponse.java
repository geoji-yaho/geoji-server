package com.ttegeoji.backend.dto;

import com.ttegeoji.backend.domain.DailyLog;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.UUID;

public record DailyLogResponse(
        UUID id, UUID roomId, LocalDate logDate, String summary, LocalTime collapseTime,
        UUID mvpUserId, OffsetDateTime createdAt
) {
    public static DailyLogResponse from(DailyLog d) {
        return new DailyLogResponse(
                d.getId(), d.getRoomId(), d.getLogDate(), d.getSummary(), d.getCollapseTime(),
                d.getMvpUserId(), d.getCreatedAt());
    }
}
