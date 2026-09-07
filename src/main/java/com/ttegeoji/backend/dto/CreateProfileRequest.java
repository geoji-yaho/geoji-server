package com.ttegeoji.backend.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record CreateProfileRequest(
        @NotBlank String nickname,
        // 생략하면 서버가 500,000원(온보딩 S-02 기본값)으로 채운다
        @Min(10000) @Max(10000000) Integer monthlyBudget
) {
}
