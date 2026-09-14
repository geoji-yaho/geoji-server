package com.ttegeoji.backend.repository;

import com.ttegeoji.backend.domain.PostRoom;
import com.ttegeoji.backend.domain.PostRoomId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PostRoomRepository extends JpaRepository<PostRoom, PostRoomId> {
    List<PostRoom> findById_PostId(UUID postId);

    boolean existsById_PostIdAndId_RoomId(UUID postId, UUID roomId);
}
