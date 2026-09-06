package com.ttegeoji.backend.api;

import com.ttegeoji.backend.domain.Comment;
import com.ttegeoji.backend.domain.Expense;
import com.ttegeoji.backend.dto.CommentResponse;
import com.ttegeoji.backend.dto.CreateCommentRequest;
import com.ttegeoji.backend.repository.CommentRepository;
import com.ttegeoji.backend.repository.ExpenseRepository;
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

// 격자 칸 댓글 = 조리돌림. 실시간 반영은 Supabase Realtime(comments 테이블 publication)이 맡는다.
@RestController
@RequestMapping("/api/expenses/{expenseId}/comments")
@RequiredArgsConstructor
public class CommentController {

    private final CommentRepository commentRepository;
    private final ExpenseRepository expenseRepository;

    @GetMapping
    public ResponseEntity<List<CommentResponse>> list(@PathVariable UUID expenseId) {
        List<CommentResponse> comments = commentRepository.findByExpenseIdOrderByCreatedAtAsc(expenseId)
                .stream().map(CommentResponse::from).toList();
        return ResponseEntity.ok(comments);
    }

    @PostMapping
    public ResponseEntity<CommentResponse> create(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID expenseId,
            @Valid @RequestBody CreateCommentRequest request) {

        Expense expense = expenseRepository.findById(expenseId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 지출 기록입니다."));

        Comment comment = Comment.builder()
                .expenseId(expenseId)
                .roomId(expense.getRoomId()) // 조인 없이 필터링하려는 비정규화 컬럼이라 여기서 채워준다
                .userId(CurrentUser.idOf(jwt))
                .content(request.content())
                .build();

        comment = commentRepository.save(comment);
        return ResponseEntity.status(HttpStatus.CREATED).body(CommentResponse.from(comment));
    }
}
