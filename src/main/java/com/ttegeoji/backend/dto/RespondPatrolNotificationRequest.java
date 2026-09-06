package com.ttegeoji.backend.dto;

import com.ttegeoji.backend.domain.enums.PatrolResponse;
import jakarta.validation.constraints.NotNull;

public record RespondPatrolNotificationRequest(
        @NotNull PatrolResponse response
) {
}
