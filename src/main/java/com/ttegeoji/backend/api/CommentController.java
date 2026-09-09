package com.ttegeoji.backend.api;

import com.ttegeoji.backend.domain.Comment;
import com.ttegeoji.backend.dto.CommentResponse;
import com.ttegeoji.backend.dto.CreateCommentRequest;
import com.ttegeoji.backend.repository.CommentRepository;
import com.ttegeoji.backend.repository.ExpenseRepository;
import com.ttegeoji.backend.repository.RoomMemberRepository;
import com.ttegeoji.backend.security.CurrentUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

// 격자 칸 댓글 = 조리돌림. 지출이 방 소유가 아니라 유저 소유라서, 댓글은 "어느 방 그리드에서
// 봤는지"를 URL로 명시해야 한다 — 같은 지출이라도 방마다 댓글 스레드가 분리된다.
// 실시간 반영은 Supabase Realtime(comments 테이블 publication)이 맡는다.
@RestController
@RequestMapping("/api/rooms/{roomId}/expenses/{expenseId}/comments")
@RequiredArgsConstructor
public class CommentController {

    private final CommentRepository commentRepository;
    private final ExpenseRepository expenseRepository;
    private final RoomMemberRepository roomMemberRepository;

    @GetMapping
    public ResponseEntity<List<CommentResponse>> list(
            @PathVariable UUID roomId, @PathVariable UUID expenseId) {
        List<CommentResponse> comments = commentRepository.findByExpenseIdOrderByCreatedAtAsc(expenseId)
                .stream()
                .filter(c -> c.getRoomId().equals(roomId))
                .map(CommentResponse::from)
                .toList();
        return ResponseEntity.ok(comments);
    }

    @PostMapping
    public ResponseEntity<CommentResponse> create(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID roomId,
            @PathVariable UUID expenseId,
            @Valid @RequestBody CreateCommentRequest request) {

        var expense = expenseRepository.findById(expenseId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 지출 기록입니다."));

        // 지출 작성자·댓글 작성자 둘 다 이 방 멤버여야 한다 — 아니면 애초에 이 방 그리드에서 안 보인다.
        if (!roomMemberRepository.existsById_RoomIdAndId_UserId(roomId, expense.getUserId())) {
            throw new IllegalArgumentException("이 지출은 해당 방에서 보이지 않습니다.");
        }
        if (!roomMemberRepository.existsById_RoomIdAndId_UserId(roomId, CurrentUser.idOf(jwt))) {
            throw new IllegalStateException("이 방의 멤버만 댓글을 달 수 있습니다.");
        }

        Comment comment = Comment.builder()
                .expenseId(expenseId)
                .roomId(roomId)
                .userId(CurrentUser.idOf(jwt))
                .content(request.content())
                .build();

        // saveAndFlush: createdAt은 DB 기본값(@Generated)이라 실제 INSERT가 나가야 채워진다.
        comment = commentRepository.saveAndFlush(comment);
        return ResponseEntity.status(HttpStatus.CREATED).body(CommentResponse.from(comment));
    }
}
