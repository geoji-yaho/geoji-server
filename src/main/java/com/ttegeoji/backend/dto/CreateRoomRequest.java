package com.ttegeoji.backend.dto;

import com.ttegeoji.backend.domain.enums.SpiceLevel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateRoomRequest(
        @NotBlank @Size(max = 20) String name,
        @NotNull SpiceLevel spiceLevel,
        @NotNull Integer voteDeadlineMinutes,
        String[] rules
) {
}
