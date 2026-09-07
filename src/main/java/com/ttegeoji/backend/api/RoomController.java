package com.ttegeoji.backend.api;

import com.ttegeoji.backend.domain.Room;
import com.ttegeoji.backend.domain.RoomMember;
import com.ttegeoji.backend.dto.CreateRoomRequest;
import com.ttegeoji.backend.dto.RoomResponse;
import com.ttegeoji.backend.repository.RoomMemberRepository;
import com.ttegeoji.backend.repository.RoomRepository;
import com.ttegeoji.backend.security.CurrentUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/rooms")
@RequiredArgsConstructor
public class RoomController {

    private final RoomRepository roomRepository;
    private final RoomMemberRepository roomMemberRepository;

    // 홈 화면(S-03)의 "방 0개 빈 상태" / 방 목록은 여기서 온다 — 내가 멤버인 방들만.
    @GetMapping
    public ResponseEntity<List<RoomResponse>> mine(@AuthenticationPrincipal Jwt jwt) {
        List<UUID> roomIds = roomMemberRepository.findById_UserId(CurrentUser.idOf(jwt))
                .stream().map(m -> m.getId().getRoomId()).toList();

        List<RoomResponse> rooms = roomRepository.findAllById(roomIds)
                .stream().map(RoomResponse::from).toList();

        return ResponseEntity.ok(rooms);
    }

    @PostMapping
    @Transactional
    public ResponseEntity<RoomResponse> create(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateRoomRequest request) {
        UUID userId = CurrentUser.idOf(jwt);

        Room room = Room.builder()
                .name(request.name())
                .spiceLevel(request.spiceLevel())
                .voteDeadlineMinutes(request.voteDeadlineMinutes())
                .rules(request.rules())
                .createdBy(userId)
                .build();
        room = roomRepository.save(room);

        roomMemberRepository.save(RoomMember.of(room.getId(), userId));

        return ResponseEntity.status(HttpStatus.CREATED).body(RoomResponse.from(room));
    }

    @GetMapping("/{roomId}")
    public ResponseEntity<RoomResponse> get(@PathVariable UUID roomId) {
        return roomRepository.findById(roomId)
                .map(RoomResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/join/{inviteCode}")
    @Transactional
    public ResponseEntity<RoomResponse> join(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String inviteCode) {
        UUID userId = CurrentUser.idOf(jwt);

        Room room = roomRepository.findByInviteCode(inviteCode)
                .orElseThrow(() -> new IllegalArgumentException("유효하지 않은 초대 코드입니다."));

        if (!roomMemberRepository.existsById_RoomIdAndId_UserId(room.getId(), userId)) {
            roomMemberRepository.save(RoomMember.of(room.getId(), userId));
        }

        return ResponseEntity.ok(RoomResponse.from(room));
    }
}
