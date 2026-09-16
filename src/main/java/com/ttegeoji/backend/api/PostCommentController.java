package com.ttegeoji.backend.api;

import com.ttegeoji.backend.comment.PostCommentService;
import com.ttegeoji.backend.comment.dto.CreatePostCommentRequest;
import com.ttegeoji.backend.security.CurrentUser;
import com.ttegeoji.backend.verdictview.PublicApiRejection;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

// SPEC 게시물 댓글. 10 에 없는 공개 API 라 AI 파트에 회신한다. 기존 expense 댓글(CommentController)과 별개다
@RestController
@RequiredArgsConstructor
public class PostCommentController {

    private final PostCommentService commentService;

    @GetMapping("/api/posts/{postId}/comments")
    public ResponseEntity<?> list(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID postId,
                                 @RequestParam(name = "room_id", required = false) UUID roomId) {
        try {
            return ResponseEntity.ok(commentService.list(postId, CurrentUser.idOf(jwt), roomId));
        } catch (PublicApiRejection e) {
            return rejection(e);
        }
    }

    @PostMapping("/api/posts/{postId}/comments")
    public ResponseEntity<?> create(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID postId,
                                    @RequestBody CreatePostCommentRequest request) {
        try {
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(commentService.create(postId, CurrentUser.idOf(jwt), request));
        } catch (PublicApiRejection e) {
            return rejection(e);
        }
    }

    @DeleteMapping("/api/posts/{postId}/comments/{commentId}")
    public ResponseEntity<?> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID postId,
                                    @PathVariable UUID commentId) {
        try {
            commentService.delete(postId, commentId, CurrentUser.idOf(jwt));
            return ResponseEntity.noContent().build();
        } catch (PublicApiRejection e) {
            return rejection(e);
        }
    }

    private static ResponseEntity<?> rejection(PublicApiRejection e) {
        return ResponseEntity.status(e.getStatus()).body(Map.of("message", e.getMessage()));
    }
}
