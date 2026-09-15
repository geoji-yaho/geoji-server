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

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/rooms")
@RequiredArgsConstructor
public class RoomController {

    private final RoomRepository roomRepository;
    private final RoomMemberRepository roomMemberRepository;

    // 홈 화면(S-03)의 "방 0개 빈 상태" / 방 목록은 여기서 온다 — 내가 멤버인, 삭제되지 않은 방들만.
    @GetMapping
    public ResponseEntity<List<RoomResponse>> mine(@AuthenticationPrincipal Jwt jwt) {
        List<UUID> roomIds = roomMemberRepository.findById_UserId(CurrentUser.idOf(jwt))
                .stream().map(m -> m.getId().getRoomId()).toList();

        List<RoomResponse> rooms = roomRepository.findAllByIdInAndDeletedAtIsNull(roomIds)
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
        // saveAndFlush: save()만 쓰면 실제 INSERT가 커밋 시점까지 미뤄져서, DB 기본값(inviteCode)이
        // @Generated로 다시 채워지기 전에 이 메서드가 응답을 만들어버린다.
        room = roomRepository.saveAndFlush(room);

        roomMemberRepository.save(RoomMember.of(room.getId(), userId));

        return ResponseEntity.status(HttpStatus.CREATED).body(RoomResponse.from(room));
    }

    @GetMapping("/{roomId}")
    public ResponseEntity<RoomResponse> get(@PathVariable UUID roomId) {
        return roomRepository.findByIdAndDeletedAtIsNull(roomId)
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
                .filter(r -> r.getDeletedAt() == null)
                .orElseThrow(() -> new IllegalArgumentException("유효하지 않은 초대 코드입니다."));

        if (!roomMemberRepository.existsById_RoomIdAndId_UserId(room.getId(), userId)) {
            roomMemberRepository.save(RoomMember.of(room.getId(), userId));
        }

        return ResponseEntity.ok(RoomResponse.from(room));
    }

    // 방 삭제(방장만). 하드 삭제하지 않는다 — votes·post_comments 가 room_id 를 물고 있어 판결·댓글
    // 기록을 지울 수 없다. 목록·조회·초대 참가에서만 숨긴다(deleted_at).
    @DeleteMapping("/{roomId}")
    @Transactional
    public ResponseEntity<?> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID roomId) {
        UUID userId = CurrentUser.idOf(jwt);

        Room room = roomRepository.findByIdAndDeletedAtIsNull(roomId).orElse(null);
        if (room == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "방을 찾을 수 없습니다."));
        }
        if (!room.getCreatedBy().equals(userId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "방장만 방을 삭제할 수 있습니다."));
        }

        room.setDeletedAt(OffsetDateTime.now());
        roomRepository.save(room);

        return ResponseEntity.noContent().build();
    }
}
