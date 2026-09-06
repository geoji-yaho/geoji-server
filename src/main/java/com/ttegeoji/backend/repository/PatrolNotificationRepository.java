package com.ttegeoji.backend.repository;

import com.ttegeoji.backend.domain.PatrolNotification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PatrolNotificationRepository extends JpaRepository<PatrolNotification, UUID> {
    List<PatrolNotification> findByRoomIdAndUserIdOrderByScheduledAtDesc(UUID roomId, UUID userId);
}
