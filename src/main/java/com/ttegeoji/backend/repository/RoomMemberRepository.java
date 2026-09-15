package com.ttegeoji.backend.repository;

import com.ttegeoji.backend.domain.RoomMember;
import com.ttegeoji.backend.domain.RoomMemberId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RoomMemberRepository extends JpaRepository<RoomMember, RoomMemberId> {
    List<RoomMember> findById_RoomId(UUID roomId);

    List<RoomMember> findById_UserId(UUID userId);

    boolean existsById_RoomIdAndId_UserId(UUID roomId, UUID userId);

    void deleteById_RoomIdAndId_UserId(UUID roomId, UUID userId);
}
