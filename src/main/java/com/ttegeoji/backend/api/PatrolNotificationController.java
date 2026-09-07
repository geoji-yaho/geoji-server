package com.ttegeoji.backend.api;

import com.ttegeoji.backend.domain.PatrolNotification;
import com.ttegeoji.backend.dto.CreatePatrolNotificationRequest;
import com.ttegeoji.backend.dto.PatrolNotificationResponse;
import com.ttegeoji.backend.dto.RespondPatrolNotificationRequest;
import com.ttegeoji.backend.repository.PatrolNotificationRepository;
import com.ttegeoji.backend.security.CurrentUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/rooms/{roomId}/patrol-notifications")
@RequiredArgsConstructor
public class PatrolNotificationController {

    private final PatrolNotificationRepository patrolNotificationRepository;

    @GetMapping("/me")
    public ResponseEntity<List<PatrolNotificationResponse>> mine(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID roomId) {

        List<PatrolNotificationResponse> notifications = patrolNotificationRepository
                .findByRoomIdAndUserIdOrderByScheduledAtDesc(roomId, CurrentUser.idOf(jwt))
                .stream().map(PatrolNotificationResponse::from).toList();

        return ResponseEntity.ok(notifications);
    }

    @PostMapping
    public ResponseEntity<PatrolNotificationResponse> create(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID roomId,
            @Valid @RequestBody CreatePatrolNotificationRequest request) {

        PatrolNotification notification = PatrolNotification.builder()
                .roomId(roomId)
                .userId(CurrentUser.idOf(jwt))
                .scheduledAt(request.scheduledAt())
                .riskReason(request.riskReason())
                .build();

        // saveAndFlush: createdAt은 DB 기본값(@Generated)이라 실제 INSERT가 나가야 채워진다.
        notification = patrolNotificationRepository.saveAndFlush(notification);
        return ResponseEntity.status(HttpStatus.CREATED).body(PatrolNotificationResponse.from(notification));
    }

    @PostMapping("/{notificationId}/respond")
    public ResponseEntity<PatrolNotificationResponse> respond(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID roomId,
            @PathVariable UUID notificationId,
            @Valid @RequestBody RespondPatrolNotificationRequest request) {

        PatrolNotification notification = patrolNotificationRepository.findById(notificationId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 알림입니다."));

        if (!notification.getUserId().equals(CurrentUser.idOf(jwt))) {
            throw new IllegalStateException("본인에게 온 알림에만 응답할 수 있습니다.");
        }

        notification.setResponse(request.response());
        notification.setRespondedAt(OffsetDateTime.now());
        notification = patrolNotificationRepository.save(notification);

        return ResponseEntity.ok(PatrolNotificationResponse.from(notification));
    }
}
