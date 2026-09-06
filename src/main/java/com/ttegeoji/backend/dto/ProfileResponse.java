package com.ttegeoji.backend.dto;

import com.ttegeoji.backend.domain.Profile;

import java.util.UUID;

public record ProfileResponse(UUID id, String nickname, String avatarUrl, Integer monthlyBudget) {
    public static ProfileResponse from(Profile p) {
        return new ProfileResponse(p.getId(), p.getNickname(), p.getAvatarUrl(), p.getMonthlyBudget());
    }
}
