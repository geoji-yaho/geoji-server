package com.ttegeoji.backend.api;

import com.ttegeoji.backend.domain.Expense;
import com.ttegeoji.backend.dto.CreateExpenseRequest;
import com.ttegeoji.backend.dto.ExpenseResponse;
import com.ttegeoji.backend.repository.ExpenseRepository;
import com.ttegeoji.backend.security.CurrentUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.UUID;

// 지출은 특정 방 소속이 아니라 유저 소속이다 — 1탭 기록에 방을 고르는 화면이 없으니, 기록하면
// 내가 속한 모든 방의 그리드에 같이 뜬다. 방별 조회는 ExpenseGridController 쪽에 있다.
@RestController
@RequestMapping("/api/expenses")
@RequiredArgsConstructor
public class ExpenseController {

    private final ExpenseRepository expenseRepository;

    @PostMapping
    public ResponseEntity<ExpenseResponse> create(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateExpenseRequest request) {

        Expense expense = Expense.builder()
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
}
