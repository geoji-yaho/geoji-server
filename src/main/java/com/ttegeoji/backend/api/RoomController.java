package com.ttegeoji.backend.api;

import com.ttegeoji.backend.domain.Profile;
import com.ttegeoji.backend.domain.Room;
import com.ttegeoji.backend.domain.RoomMember;
import com.ttegeoji.backend.dto.CreateRoomRequest;
import com.ttegeoji.backend.dto.RoomInvitePreviewResponse;
import com.ttegeoji.backend.dto.RoomResponse;
import com.ttegeoji.backend.repository.ProfileRepository;
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

    /**
     * 한 사람이 들어갈 수 있는 방 수(9/17 사용자 결정). 재판이 방마다 따로 돌아가므로 게시물 하나가
     * 방 수만큼 AI 를 부른다 — 방 3개면 판결도 3건, 토큰도 3배다. 그 비용을 여기서 막는다.
     * 삭제된 방은 세지 않으므로 방을 지우면 자리가 다시 생긴다.
     */
    static final int MAX_ROOMS_PER_USER = 3;

    private void checkRoomLimit(UUID userId) {
        if (roomMemberRepository.countActiveRoomsOfUser(userId) >= MAX_ROOMS_PER_USER) {
            throw new IllegalStateException(
                    "방은 최대 " + MAX_ROOMS_PER_USER + "개까지 참여할 수 있습니다. 쓰지 않는 방에서 나간 뒤 다시 시도해 주세요.");
        }
    }
    private final ProfileRepository profileRepository;

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
        checkRoomLimit(userId);

        Room room = Room.builder()
                .name(request.name())
                .spiceLevel(request.spiceLevel())
                .voteDeadlineMinutes(request.voteDeadlineMinutes())
                // rules 는 DB 에서 NOT NULL DEFAULT '{}' 인데 Hibernate 는 null 을 그대로 넣어 기본값이 안 먹는다
                .rules(request.rules() != null ? request.rules() : new String[0])
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

    // 초대장 화면(S-05) 미리보기 — 참가 전이라 아직 멤버가 아닌 사람도 볼 수 있다.
    // 초대 코드만 알고 roomId 는 모르는 상태라 GET /api/rooms/{roomId} 로는 대신할 수 없다.
    @GetMapping("/invite/{inviteCode}")
    public ResponseEntity<RoomInvitePreviewResponse> preview(
            @AuthenticationPrincipal Jwt jwt, @PathVariable String inviteCode) {
        UUID userId = CurrentUser.idOf(jwt);

        Room room = roomRepository.findByInviteCode(inviteCode)
                .filter(r -> r.getDeletedAt() == null)
                // 참가(join)와 같은 문구·상태를 쓴다. 프론트가 만료된 초대 링크로 같이 처리한다
                .orElseThrow(() -> new IllegalArgumentException("유효하지 않은 초대 코드입니다."));

        String ownerNickname = profileRepository.findById(room.getCreatedBy())
                .map(Profile::getNickname)
                .orElse(null);

        return ResponseEntity.ok(RoomInvitePreviewResponse.from(
                room,
                ownerNickname,
                roomMemberRepository.countById_RoomId(room.getId()),
                roomMemberRepository.existsById_RoomIdAndId_UserId(room.getId(), userId)));
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

        // 이미 멤버면 상한과 무관하게 그대로 통과시킨다(재입장이 아니라 방 정보 조회에 가깝다)
        if (!roomMemberRepository.existsById_RoomIdAndId_UserId(room.getId(), userId)) {
            checkRoomLimit(userId);
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
