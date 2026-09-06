package com.ttegeoji.backend.api;

import com.ttegeoji.backend.domain.Challenge;
import com.ttegeoji.backend.domain.enums.ChallengeStatus;
import com.ttegeoji.backend.dto.ChallengeResponse;
import com.ttegeoji.backend.dto.CreateChallengeRequest;
import com.ttegeoji.backend.repository.ChallengeRepository;
import com.ttegeoji.backend.security.CurrentUser;
import com.ttegeoji.backend.util.Json;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

// 도전 과제 산출(절감 항목 계산 + 서술 생성)은 이 컨트롤러의 책임이 아니다. 결과를 저장/조회/수락만 한다.
@RestController
@RequestMapping("/api/rooms/{roomId}/challenges")
@RequiredArgsConstructor
public class ChallengeController {

    private final ChallengeRepository challengeRepository;

    @GetMapping("/me")
    public ResponseEntity<ChallengeResponse> mine(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID roomId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekStart) {

        return challengeRepository.findByRoomIdAndUserIdAndWeekStart(roomId, CurrentUser.idOf(jwt), weekStart)
                .map(ChallengeResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<ChallengeResponse> create(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID roomId,
            @Valid @RequestBody CreateChallengeRequest request) {

        Challenge challenge = Challenge.builder()
                .roomId(roomId)
                .userId(CurrentUser.idOf(jwt))
                .weekStart(request.weekStart())
                .items(Json.write(request.items()))
                .narrative(request.narrative())
                .targetAmount(request.targetAmount())
                .status(ChallengeStatus.pending)
                .build();

        challenge = challengeRepository.save(challenge);
        return ResponseEntity.status(HttpStatus.CREATED).body(ChallengeResponse.from(challenge));
    }

    @PostMapping("/{challengeId}/accept")
    public ResponseEntity<ChallengeResponse> accept(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID roomId,
            @PathVariable UUID challengeId) {

        Challenge challenge = challengeRepository.findById(challengeId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 도전 과제입니다."));

        if (!challenge.getUserId().equals(CurrentUser.idOf(jwt))) {
            throw new IllegalStateException("본인의 도전 과제만 수락할 수 있습니다.");
        }

        challenge.setStatus(ChallengeStatus.accepted);
        challenge.setAcceptedAt(OffsetDateTime.now());
        challenge = challengeRepository.save(challenge);

        return ResponseEntity.ok(ChallengeResponse.from(challenge));
    }
}
