package com.ttegeoji.backend.api;

import com.ttegeoji.backend.domain.Expense;
import com.ttegeoji.backend.dto.CreateExpenseRequest;
import com.ttegeoji.backend.dto.ExpenseResponse;
import com.ttegeoji.backend.repository.ExpenseRepository;
import com.ttegeoji.backend.security.CurrentUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/rooms/{roomId}/expenses")
@RequiredArgsConstructor
public class ExpenseController {

    private final ExpenseRepository expenseRepository;

    @PostMapping
    public ResponseEntity<ExpenseResponse> create(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID roomId,
            @Valid @RequestBody CreateExpenseRequest request) {

        Expense expense = Expense.builder()
                .roomId(roomId)
                .userId(CurrentUser.idOf(jwt))
                .amount(request.amount())
                .category(request.category())
                .memo(request.memo())
                .source(request.source())
                .spentAt(request.spentAt() != null ? request.spentAt() : OffsetDateTime.now())
                .build();

        expense = expenseRepository.save(expense);
        return ResponseEntity.status(HttpStatus.CREATED).body(ExpenseResponse.from(expense));
    }

    // 홈 그리드: 멤버 x 시간대 지출을 그리기 위한 기간 조회
    @GetMapping
    public ResponseEntity<List<ExpenseResponse>> listForGrid(
            @PathVariable UUID roomId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to) {

        List<ExpenseResponse> expenses = expenseRepository
                .findByRoomIdAndSpentAtBetweenOrderBySpentAtDesc(roomId, from, to)
                .stream()
                .map(ExpenseResponse::from)
                .toList();

        return ResponseEntity.ok(expenses);
    }
}
