package com.ttegeoji.backend.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record CreateProfileRequest(
        // 생략하면 온보딩 때는 소셜 로그인(카카오 등) 메타데이터의 닉네임으로 채운다.
        // 그것도 없으면(이메일 가입 등) 400 — 그때는 클라이언트가 직접 받아서 보내야 한다.
        String nickname,
        // 생략하면 서버가 500,000원(온보딩 S-02 기본값)으로 채운다
        @Min(10000) @Max(10000000) Integer monthlyBudget
) {
}
