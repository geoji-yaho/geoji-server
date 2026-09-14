package com.ttegeoji.backend.api;

import com.ttegeoji.backend.security.CurrentUser;
import com.ttegeoji.backend.submission.SubmissionService;
import com.ttegeoji.backend.submission.dto.CompleteRequest;
import com.ttegeoji.backend.submission.dto.SubmissionResponse;
import com.ttegeoji.backend.submission.dto.SubmitRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

// 게시물 제출(10 §9). actor 는 JWT 사용자. 트랜잭션을 걸지 않는다 — intake HTTP 가 안에 있다(10 §2)
@RestController
@RequestMapping("/api/post-submissions")
@RequiredArgsConstructor
public class PostSubmissionController {

    private final SubmissionService submissionService;

    @PostMapping
    public ResponseEntity<SubmissionResponse> submit(@AuthenticationPrincipal Jwt jwt,
                                                     @RequestBody SubmitRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(submissionService.submit(CurrentUser.idOf(jwt), request));
    }

    @PostMapping("/{submissionId}/complete")
    public ResponseEntity<SubmissionResponse> complete(@AuthenticationPrincipal Jwt jwt,
                                                       @PathVariable UUID submissionId,
                                                       @RequestBody CompleteRequest request) {
        return ResponseEntity.ok(submissionService.complete(CurrentUser.idOf(jwt), submissionId, request));
    }
}
