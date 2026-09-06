package com.ttegeoji.backend.dto;

import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;

public record CreatePatrolNotificationRequest(
        @NotNull OffsetDateTime scheduledAt,
        String riskReason // LLM이 생성한 안내 문구. 위험 시각 계산 자체는 이 API 밖에서 통계로 끝난 상태여야 한다.
) {
}
