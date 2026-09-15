package com.ttegeoji.backend.api;

import com.ttegeoji.backend.security.CurrentUser;
import com.ttegeoji.backend.verdictview.PublicApiRejection;
import com.ttegeoji.backend.verdictview.VerdictViewAssembler;
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

// 10 §9 판결 폴링 조회. 전달은 폴링만(Realtime·SSE 없음)
@RestController
@RequiredArgsConstructor
public class PostVerdictController {

    private final VerdictViewAssembler assembler;

    @GetMapping("/api/posts/{postId}/verdict")
    public ResponseEntity<?> getVerdict(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID postId,
                                        @RequestParam(name = "room_id", required = false) UUID roomId) {
        try {
            return ResponseEntity.ok(assembler.assemble(postId, CurrentUser.idOf(jwt), roomId));
        } catch (PublicApiRejection e) {
            return ResponseEntity.status(e.getStatus()).body(Map.of("message", e.getMessage()));
        }
    }
}
