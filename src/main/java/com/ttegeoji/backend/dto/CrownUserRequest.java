package com.ttegeoji.backend.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CrownUserRequest(
        @NotNull UUID userId,
        UUID dethronedByExpenseId // 폐위 속보 카드 근거. 없어도 됨 (예: 시즌 초 최초 즉위)
) {
}
