package com.ttegeoji.backend.api;

import com.ttegeoji.backend.domain.Profile;
import com.ttegeoji.backend.domain.RoomMember;
import com.ttegeoji.backend.dto.RoomMemberResponse;
import com.ttegeoji.backend.repository.ProfileRepository;
import com.ttegeoji.backend.repository.RoomMemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
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

    @GetMapping
    public ResponseEntity<List<RoomMemberResponse>> list(@PathVariable UUID roomId) {
        List<RoomMember> members = roomMemberRepository.findById_RoomId(roomId);

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
                            m.getDebtScore(),
                            m.getJoinedAt());
                })
                // 거지력 높은 순. null(아직 배치가 안 돈 멤버)은 맨 뒤로 보낸다.
                .sorted(Comparator.comparing(RoomMemberResponse::debtScore,
                        Comparator.nullsLast(Comparator.<BigDecimal>reverseOrder())))
                .toList();

        return ResponseEntity.ok(response);
    }
}
