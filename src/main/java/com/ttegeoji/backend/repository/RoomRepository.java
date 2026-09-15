package com.ttegeoji.backend.repository;

import com.ttegeoji.backend.domain.Room;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RoomRepository extends JpaRepository<Room, UUID> {
    Optional<Room> findByInviteCode(String inviteCode);

    List<Room> findAllByIdInAndDeletedAtIsNull(List<UUID> ids);

    Optional<Room> findByIdAndDeletedAtIsNull(UUID id);
}
