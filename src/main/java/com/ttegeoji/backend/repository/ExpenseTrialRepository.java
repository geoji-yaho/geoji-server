package com.ttegeoji.backend.repository;

import com.ttegeoji.backend.domain.ExpenseTrial;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ExpenseTrialRepository extends JpaRepository<ExpenseTrial, UUID> {
    Optional<ExpenseTrial> findByRoomIdAndExpenseId(UUID roomId, UUID expenseId);
}
