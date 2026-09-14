package com.ttegeoji.backend.repository;

import com.ttegeoji.backend.domain.Vote;
import com.ttegeoji.backend.domain.enums.VerdictType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface VoteRepository extends JpaRepository<Vote, UUID> {
    List<Vote> findByPostId(UUID postId);

    long countByPostId(UUID postId);

    long countByPostIdAndVerdict(UUID postId, VerdictType verdict);

    boolean existsByPostIdAndVoterId(UUID postId, UUID voterId);
}
