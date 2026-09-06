package com.ttegeoji.backend.repository;

import com.ttegeoji.backend.domain.Expense;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface ExpenseRepository extends JpaRepository<Expense, UUID> {
    // 그리드 화면: 방의 최근 지출을 시간 내림차순으로
    List<Expense> findByRoomIdAndSpentAtBetweenOrderBySpentAtDesc(
            UUID roomId, OffsetDateTime from, OffsetDateTime to);
}
