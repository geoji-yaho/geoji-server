package com.ttegeoji.backend.api;

import com.ttegeoji.backend.seed.AiMemberService;
import com.ttegeoji.backend.seed.AiMemberService.Result;
import com.ttegeoji.backend.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 19 §6 '데모용 AI 유저 추가' 버튼. 요청자(JWT)가 그 방 멤버여야 한다. 본문 없음.
 * 201 새로 만듦 / 200 이미 다 있음(멱등), 둘 다 {userId, nickname, postIds}.
 * 503 {"code": "AI_JUROR_NOT_CONFIGURED"} 만 예외적으로 code 본문이다(19 §6 표 — 프론트가 버튼을 숨기는 신호).
 */
@RestController
@RequiredArgsConstructor
public class RoomAiMemberController {

    public record AiMemberResponse(UUID userId, String nickname, List<UUID> postIds) {
    }

    private final AiMemberService aiMemberService;

    @PostMapping("/api/rooms/{roomId}/ai-member")
    public ResponseEntity<?> add(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID roomId) {
        try {
            Result result = aiMemberService.add(roomId, CurrentUser.idOf(jwt));
            return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                    .body(new AiMemberResponse(result.userId(), result.nickname(), result.postIds()));
        } catch (AiMemberService.NotConfiguredException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("code", e.getMessage()));
        } catch (AiMemberService.RoomNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", e.getMessage()));
        }
    }
}
