package com.ttegeoji.backend.api;

import com.ttegeoji.backend.security.CurrentUser;
import com.ttegeoji.backend.verdictview.PostVoteService;
import com.ttegeoji.backend.verdictview.PublicApiRejection;
import com.ttegeoji.backend.verdictview.dto.PostVoteRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

// SPEC S-14 배심원 투표. 10 에 없는 공개 API 라 AI 파트에 회신한다
@RestController
@RequiredArgsConstructor
public class PostVoteController {

    private final PostVoteService voteService;

    @PostMapping("/api/posts/{postId}/votes")
    public ResponseEntity<?> castVote(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID postId,
                                      @RequestBody PostVoteRequest request) {
        try {
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(voteService.cast(postId, CurrentUser.idOf(jwt), request));
        } catch (PublicApiRejection e) {
            return ResponseEntity.status(e.getStatus()).body(Map.of("message", e.getMessage()));
        } catch (DuplicateKeyException e) {
            // 게시물 잠금으로 직렬화되지만 votes UNIQUE 위반이 나도 같은 409 로
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", "이미 투표했습니다."));
        }
    }
}
