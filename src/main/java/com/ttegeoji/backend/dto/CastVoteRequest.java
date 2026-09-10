package com.ttegeoji.backend.dto;

import com.ttegeoji.backend.domain.enums.VerdictType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CastVoteRequest(
        @NotNull VerdictType verdict,
        @NotBlank @Size(max = 500) String reason
) {
}
