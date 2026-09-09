package com.ttegeoji.backend.dto;

import com.ttegeoji.backend.domain.Expense;
import com.ttegeoji.backend.domain.enums.ExpenseSource;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ExpenseResponse(
        UUID id,
        UUID userId,
        Integer amount,
        String category,
        String memo,
        ExpenseSource source,
        OffsetDateTime spentAt
) {
    public static ExpenseResponse from(Expense e) {
        return new ExpenseResponse(
                e.getId(), e.getUserId(), e.getAmount(),
                e.getCategory(), e.getMemo(), e.getSource(), e.getSpentAt());
    }
}
