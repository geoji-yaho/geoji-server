package com.ttegeoji.backend.api;

import com.ttegeoji.backend.domain.Profile;
import com.ttegeoji.backend.domain.RoomMember;
import com.ttegeoji.backend.dto.RoomMemberResponse;
import com.ttegeoji.backend.repository.ProfileRepository;
import com.ttegeoji.backend.repository.RoomMemberRepository;
import com.ttegeoji.backend.security.CurrentUser;
import com.ttegeoji.backend.verdictview.DebtScoreQueries;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/rooms/{roomId}/members")
@RequiredArgsConstructor
public class RoomMemberController {

    private final RoomMemberRepository roomMemberRepository;
    private final ProfileRepository profileRepository;
    private final DebtScoreQueries debtScoreQueries;

    @GetMapping
    public ResponseEntity<List<RoomMemberResponse>> list(@PathVariable UUID roomId) {
        List<RoomMember> members = roomMemberRepository.findById_RoomId(roomId);

        Map<UUID, BigDecimal> scores = debtScoreQueries.forRoom(roomId);

        List<UUID> userIds = members.stream().map(m -> m.getId().getUserId()).toList();
        Map<UUID, Profile> profilesById = profileRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(Profile::getId, Function.identity()));

        List<RoomMemberResponse> response = members.stream()
                .map(m -> {
                    Profile profile = profilesById.get(m.getId().getUserId());
                    return new RoomMemberResponse(
                            m.getId().getUserId(),
                            profile != null ? profile.getNickname() : null,
                            profile != null ? profile.getAvatarUrl() : null,
                            scores.get(m.getId().getUserId()),
                            m.getJoinedAt());
                })
                // 거지력 높은 순. null(예산을 아직 안 정한 멤버)은 맨 뒤로 보낸다.
                .sorted(Comparator.comparing(RoomMemberResponse::debtScore,
                        Comparator.nullsLast(Comparator.<BigDecimal>reverseOrder())))
                .toList();

        return ResponseEntity.ok(response);
    }

    // 방 탈퇴. 방장이 나가도 방은 그대로 남는다(방 삭제는 별도 API, RoomController).
    @DeleteMapping("/me")
    @Transactional
    public ResponseEntity<?> leave(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID roomId) {
        UUID userId = CurrentUser.idOf(jwt);

        if (!roomMemberRepository.existsById_RoomIdAndId_UserId(roomId, userId)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "이 방의 멤버가 아닙니다."));
        }

        roomMemberRepository.deleteById_RoomIdAndId_UserId(roomId, userId);

        return ResponseEntity.noContent().build();
    }
}
