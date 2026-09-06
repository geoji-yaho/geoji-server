package com.ttegeoji.backend.dto;

import com.ttegeoji.backend.domain.enums.ExpenseSource;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;

public record CreateExpenseRequest(
        @NotNull @Min(0) Integer amount,
        String category,
        String memo,
        @NotNull ExpenseSource source,
        OffsetDateTime spentAt // null이면 서버가 now()로 채운다 (1탭 기록의 기본 경로)
) {
}
