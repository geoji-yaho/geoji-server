package com.ttegeoji.backend.dto;

import com.ttegeoji.backend.domain.PatrolNotification;
import com.ttegeoji.backend.domain.enums.PatrolResponse;

import java.time.OffsetDateTime;
import java.util.UUID;

public record PatrolNotificationResponse(
        UUID id, UUID roomId, UUID userId, OffsetDateTime scheduledAt, OffsetDateTime sentAt,
        String riskReason, PatrolResponse response, OffsetDateTime respondedAt
) {
    public static PatrolNotificationResponse from(PatrolNotification n) {
        return new PatrolNotificationResponse(
                n.getId(), n.getRoomId(), n.getUserId(), n.getScheduledAt(), n.getSentAt(),
                n.getRiskReason(), n.getResponse(), n.getRespondedAt());
    }
}
