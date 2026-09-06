package com.ttegeoji.backend.repository;

import com.ttegeoji.backend.domain.WeeklyAward;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface WeeklyAwardRepository extends JpaRepository<WeeklyAward, UUID> {
    List<WeeklyAward> findByRoomIdAndWeekStart(UUID roomId, LocalDate weekStart);
}
