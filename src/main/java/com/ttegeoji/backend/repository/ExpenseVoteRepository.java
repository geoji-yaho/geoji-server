package com.ttegeoji.backend.repository;

import com.ttegeoji.backend.domain.ExpenseVote;
import com.ttegeoji.backend.domain.enums.VerdictType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ExpenseVoteRepository extends JpaRepository<ExpenseVote, UUID> {
    List<ExpenseVote> findByTrialIdOrderByCreatedAtAsc(UUID trialId);

    boolean existsByTrialIdAndVoterUserId(UUID trialId, UUID voterUserId);

    long countByTrialIdAndVerdict(UUID trialId, VerdictType verdict);
}
