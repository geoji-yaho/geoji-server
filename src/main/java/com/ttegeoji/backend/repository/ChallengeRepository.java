package com.ttegeoji.backend.repository;

import com.ttegeoji.backend.domain.Challenge;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface ChallengeRepository extends JpaRepository<Challenge, UUID> {
    Optional<Challenge> findByRoomIdAndUserIdAndWeekStart(UUID roomId, UUID userId, LocalDate weekStart);
}
