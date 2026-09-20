package com.ttegeoji.backend.api.internal;

import com.ttegeoji.backend.config.InternalApiException;
import com.ttegeoji.backend.verdictview.JuryVoteService;
import com.ttegeoji.backend.verdictview.dto.JuryVoteRequest;
import com.ttegeoji.backend.verdictview.dto.PostVoteResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * 19 §5 떼거지봇 표 — 워커가 JURY_VOTE job 하나당 한 번 부른다. 인증은 ServiceTokenFilter 가 먼저 끝낸다(10 §4.7).
 * 응답 201 {"vote_id"}. 거부 본문은 {"code"} 하나. 여기서는 아무것도 로그하지 않는다(사유 원문·토큰 보호).
 * 순서는 다른 내부 API 와 같게 본문 스키마 422 → job 409(19 §5 1단계) → 그 뒤 단계.
 */
@RestController
@RequiredArgsConstructor
public class JuryVoteController {

    private final JuryVoteService juryVoteService;

    @PostMapping("/internal/v1/posts/{post_id}/jury-votes")
    public ResponseEntity<Map<String, String>> cast(
            @PathVariable("post_id") String postId,
            @RequestHeader(value = AiJobController.GENERATION_HEADER, required = false) String generationId,
            @RequestBody(required = false) byte[] body) {
        JuryVoteRequest request = JuryVoteRequest.parse(body);
        // 경로 post_id 가 UUID 가 아니면 그런 글은 없다 — 삭제된 글과 같은 404. 다만 job 검증(1단계)이 먼저다
        UUID post = strictUuid(postId);
        UUID generation = strictUuid(generationId);
        PostVoteResponse vote = juryVoteService.cast(post, generation, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("vote_id", vote.id()));
    }

    // AiJobController 와 같은 이유로 표준 36자만 받는다
    private static UUID strictUuid(String value) {
        if (value == null || value.length() != 36) {
            return null;
        }
        try {
            UUID uuid = UUID.fromString(value);
            return uuid.toString().equalsIgnoreCase(value) ? uuid : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
