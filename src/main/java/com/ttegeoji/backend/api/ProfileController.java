package com.ttegeoji.backend.api;

import com.ttegeoji.backend.domain.Profile;
import com.ttegeoji.backend.dto.CreateProfileRequest;
import com.ttegeoji.backend.dto.ProfileResponse;
import com.ttegeoji.backend.repository.ProfileRepository;
import com.ttegeoji.backend.security.CurrentUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

@RestController
@RequestMapping("/api/me")
@RequiredArgsConstructor
public class ProfileController {

    private static final int DEFAULT_MONTHLY_BUDGET = 500000; // 온보딩 S-02 기본값

    private final ProfileRepository profileRepository;

    @GetMapping
    public ResponseEntity<ProfileResponse> me(@AuthenticationPrincipal Jwt jwt) {
        Profile profile = profileRepository.findById(CurrentUser.idOf(jwt))
                .orElseThrow(() -> new IllegalStateException("프로필이 없습니다. 온보딩을 먼저 완료해야 합니다."));
        return ResponseEntity.ok(ProfileResponse.from(profile));
    }

    // 온보딩(S-01 로그인 직후 ~ S-02 월예산). Supabase Auth 가입만으로는 profiles에 행이 안 생기므로
    // 첫 로그인 후 반드시 한 번 호출해야 room 생성/지출 기록 등 나머지 기능이 동작한다.
    @PostMapping
    public ResponseEntity<ProfileResponse> onboard(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateProfileRequest request) {

        if (profileRepository.existsById(CurrentUser.idOf(jwt))) {
            throw new IllegalStateException("이미 온보딩이 완료된 계정입니다.");
        }

        // 카카오 등 소셜 로그인이면 Supabase가 JWT에 넣어준 프로필(닉네임·프로필사진)을 그대로 쓴다.
        // 요청에 nickname을 직접 보냈으면 그게 우선이다 (온보딩 화면에서 수정했을 수 있으므로).
        String nickname = request.nickname() != null && !request.nickname().isBlank()
                ? request.nickname()
                : CurrentUser.nicknameOf(jwt);
        if (nickname == null || nickname.isBlank()) {
            throw new IllegalArgumentException("닉네임을 입력해주세요.");
        }

        Profile profile = Profile.builder()
                .id(CurrentUser.idOf(jwt))
                .nickname(nickname)
                .avatarUrl(CurrentUser.avatarUrlOf(jwt))
                .monthlyBudget(request.monthlyBudget() != null ? request.monthlyBudget() : DEFAULT_MONTHLY_BUDGET)
                .build();

        profile = profileRepository.save(profile);
        return ResponseEntity.status(HttpStatus.CREATED).body(ProfileResponse.from(profile));
    }

    // 닉네임/월예산 수정 (마이 화면 등에서 사용)
    @PutMapping
    public ResponseEntity<ProfileResponse> update(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateProfileRequest request) {

        Profile profile = profileRepository.findById(CurrentUser.idOf(jwt))
                .orElseThrow(() -> new IllegalStateException("프로필이 없습니다. 온보딩을 먼저 완료해야 합니다."));

        if (request.nickname() != null && !request.nickname().isBlank()) {
            profile.setNickname(request.nickname());
        }
        if (request.monthlyBudget() != null) {
            profile.setMonthlyBudget(request.monthlyBudget());
        }

        profile = profileRepository.save(profile);
        return ResponseEntity.ok(ProfileResponse.from(profile));
    }
}
