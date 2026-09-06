package com.ttegeoji.backend.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public record CreateChallengeRequest(
        @NotNull LocalDate weekStart,
        // [{label, savedAmount}] 형태의 JSON 배열. 금액은 통계 계산 결과를 슬롯으로 주입한 것이라 여기서 생성하지 않는다.
        @NotNull Object items,
        String narrative,
        @NotNull @Min(0) Integer targetAmount
) {
}
