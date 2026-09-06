package com.ttegeoji.backend.repository;

import com.ttegeoji.backend.domain.DailyLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface DailyLogRepository extends JpaRepository<DailyLog, UUID> {
    Optional<DailyLog> findByRoomIdAndLogDate(UUID roomId, LocalDate logDate);
}
