package com.ttegeoji.backend.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

public record CreateDailyLogRequest(
        @NotNull LocalDate logDate,
        String summary,
        LocalTime collapseTime,
        UUID mvpUserId
) {
}
