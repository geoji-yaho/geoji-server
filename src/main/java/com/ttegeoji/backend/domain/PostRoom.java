package com.ttegeoji.backend.domain;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.*;

import java.util.UUID;

// 게시물이 공유된 방. 10 §2 에 없는 테이블(9/14 결정, AI 파트 회신 대상)
@Entity
@Table(name = "post_rooms")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PostRoom {

    @EmbeddedId
    private PostRoomId id;

    public static PostRoom of(UUID postId, UUID roomId) {
        return PostRoom.builder()
                .id(new PostRoomId(postId, roomId))
                .build();
    }
}
