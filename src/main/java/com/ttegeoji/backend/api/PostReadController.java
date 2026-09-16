package com.ttegeoji.backend.api;

import com.ttegeoji.backend.security.CurrentUser;
import com.ttegeoji.backend.verdictview.PostReadService;
import com.ttegeoji.backend.verdictview.PublicApiRejection;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

// 게시물 읽기. 판결문은 PostVerdictController, 공유 카드는 ShareCardController 가 준다
@RestController
@RequiredArgsConstructor
public class PostReadController {

    private final PostReadService service;

    @GetMapping("/api/posts/{postId}")
    public ResponseEntity<?> detail(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID postId,
                                   @RequestParam(name = "room_id", required = false) UUID roomId) {
        try {
            return ResponseEntity.ok(service.detail(postId, CurrentUser.idOf(jwt), roomId));
        } catch (PublicApiRejection e) {
            return ResponseEntity.status(e.getStatus()).body(Map.of("message", e.getMessage()));
        }
    }

    @GetMapping("/api/rooms/{roomId}/posts")
    public ResponseEntity<?> roomFeed(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID roomId) {
        try {
            return ResponseEntity.ok(service.roomFeed(roomId, CurrentUser.idOf(jwt)));
        } catch (PublicApiRejection e) {
            return ResponseEntity.status(e.getStatus()).body(Map.of("message", e.getMessage()));
        }
    }
}
