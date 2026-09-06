package com.ttegeoji.backend.api;

import com.ttegeoji.backend.domain.Profile;
import com.ttegeoji.backend.dto.ProfileResponse;
import com.ttegeoji.backend.repository.ProfileRepository;
import com.ttegeoji.backend.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

@RestController
@RequestMapping("/api/me")
@RequiredArgsConstructor
public class ProfileController {

    private final ProfileRepository profileRepository;

    @GetMapping
    public ResponseEntity<ProfileResponse> me(@AuthenticationPrincipal Jwt jwt) {
        Profile profile = profileRepository.findById(CurrentUser.idOf(jwt))
                .orElseThrow(() -> new IllegalStateException("프로필이 없습니다. 온보딩을 먼저 완료해야 합니다."));
        return ResponseEntity.ok(ProfileResponse.from(profile));
    }
}
