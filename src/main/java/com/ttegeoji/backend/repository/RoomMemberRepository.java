package com.ttegeoji.backend.repository;

import com.ttegeoji.backend.domain.RoomMember;
import com.ttegeoji.backend.domain.RoomMemberId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface RoomMemberRepository extends JpaRepository<RoomMember, RoomMemberId> {
    List<RoomMember> findById_RoomId(UUID roomId);

    List<RoomMember> findById_UserId(UUID userId);

    boolean existsById_RoomIdAndId_UserId(UUID roomId, UUID userId);

    void deleteById_RoomIdAndId_UserId(UUID roomId, UUID userId);

    long countById_RoomId(UUID roomId);

    /**
     * 참여 중인 방 수. 삭제된 방은 빼므로 방을 지우면 자리가 다시 생긴다.
     * 참여 상한(10 §16 밖, 9/17 사용자 결정)을 재는 데 쓴다.
     */
    @Query("""
            SELECT count(m) FROM RoomMember m, Room r
             WHERE r.id = m.id.roomId AND m.id.userId = :userId AND r.deletedAt IS NULL""")
    long countActiveRoomsOfUser(@Param("userId") UUID userId);
}
