package com.ttegeoji.backend.repository;

import com.ttegeoji.backend.domain.CrownHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CrownHistoryRepository extends JpaRepository<CrownHistory, UUID> {
    Optional<CrownHistory> findByRoomIdAndEndedAtIsNull(UUID roomId);

    List<CrownHistory> findByRoomIdOrderByStartedAtDesc(UUID roomId);
}
