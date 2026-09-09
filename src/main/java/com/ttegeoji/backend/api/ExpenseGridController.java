package com.ttegeoji.backend.api;

import com.ttegeoji.backend.dto.ExpenseResponse;
import com.ttegeoji.backend.repository.ExpenseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

// 홈 그리드: 이 방 "멤버들"의 지출을 기간으로 조회. 지출 생성은 ExpenseController(/api/expenses) 쪽에 있다.
@RestController
@RequestMapping("/api/rooms/{roomId}/expenses")
@RequiredArgsConstructor
public class ExpenseGridController {

    private final ExpenseRepository expenseRepository;

    @GetMapping
    public ResponseEntity<List<ExpenseResponse>> listForGrid(
            @PathVariable UUID roomId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to) {

        List<ExpenseResponse> expenses = expenseRepository
                .findForRoomGrid(roomId, from, to)
                .stream()
                .map(ExpenseResponse::from)
                .toList();

        return ResponseEntity.ok(expenses);
    }
}
