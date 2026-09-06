package com.ttegeoji.backend.dto;

import com.ttegeoji.backend.domain.enums.AwardType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.UUID;

public record CreateWeeklyAwardRequest(
        @NotNull LocalDate weekStart,
        @NotNull LocalDate weekEnd,
        @NotNull AwardType awardType,
        @NotBlank String title,
        UUID winnerUserId,
        String description,
        // 상 발명의 근거가 된 통계 후보 데이터. 임의의 JSON(객체/배열) 아무거나 허용한다.
        @NotNull Object statsSnapshot
) {
}
