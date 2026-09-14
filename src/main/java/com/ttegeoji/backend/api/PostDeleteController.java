package com.ttegeoji.backend.api;

import com.ttegeoji.backend.privacy.InvalidationService;
import com.ttegeoji.backend.security.CurrentUser;
import com.ttegeoji.backend.verdictview.VerdictViewQueries;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

// 10 §8 게시물 삭제. epoch·원본 비활성화·진행 중 job 끄기는 InvalidationService 한 트랜잭션이다
@RestController
@RequiredArgsConstructor
public class PostDeleteController {

    private final VerdictViewQueries queries;
    private final InvalidationService invalidationService;

    @DeleteMapping("/api/posts/{postId}")
    public ResponseEntity<?> deletePost(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID postId) {
        if (queries.findPost(postId).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "게시물을 찾을 수 없습니다."));
        }
        try {
            // 이미 삭제된 게시물을 작성자가 다시 지우면 서비스가 아무것도 하지 않고 성공한다
            invalidationService.deletePost(postId, CurrentUser.idOf(jwt));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", e.getMessage()));
        }
        return ResponseEntity.noContent().build();
    }
}
