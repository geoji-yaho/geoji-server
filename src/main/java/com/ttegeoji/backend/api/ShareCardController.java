package com.ttegeoji.backend.api;

import com.ttegeoji.backend.security.CurrentUser;
import com.ttegeoji.backend.verdictview.PublicApiRejection;
import com.ttegeoji.backend.verdictview.ShareCardAssembler;
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

// 10 §9 공유 카드. Evidence 원문·개인 이력은 응답 타입에 필드가 없다
@RestController
@RequiredArgsConstructor
public class ShareCardController {

    private final ShareCardAssembler assembler;

    @GetMapping("/api/posts/{postId}/share-card")
    public ResponseEntity<?> getShareCard(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID postId,
                                          @RequestParam(name = "room_id", required = false) UUID roomId) {
        try {
            return ResponseEntity.ok(assembler.assemble(postId, CurrentUser.idOf(jwt), roomId));
        } catch (PublicApiRejection e) {
            return ResponseEntity.status(e.getStatus()).body(Map.of("message", e.getMessage()));
        }
    }
}
