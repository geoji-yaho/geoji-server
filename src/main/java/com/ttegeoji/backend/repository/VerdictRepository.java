package com.ttegeoji.backend.repository;

import com.ttegeoji.backend.domain.Verdict;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface VerdictRepository extends JpaRepository<Verdict, UUID> {
    Optional<Verdict> findByPostId(UUID postId);

    // SELECT ... FOR UPDATE. 잠금 순서는 privacy scope 행 → 이 행 → job 행(10 §2)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from Verdict v where v.id = :id")
    Optional<Verdict> findByIdForUpdate(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from Verdict v where v.postId = :postId")
    Optional<Verdict> findByPostIdForUpdate(@Param("postId") UUID postId);
}
