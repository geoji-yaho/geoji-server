package com.ttegeoji.backend.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "room_members")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RoomMember {

    @EmbeddedId
    private RoomMemberId id;

    // 실시간 계산값이 아니라 주간 배치가 캐싱해두는 스냅샷. 배치 잡 외에는 쓰지 않는다.
    @Column(name = "debt_score")
    private BigDecimal debtScore;

    @Generated(event = EventType.INSERT)
    @Column(name = "joined_at", insertable = false, updatable = false)
    private OffsetDateTime joinedAt;

    public static RoomMember of(UUID roomId, UUID userId) {
        return RoomMember.builder()
                .id(new RoomMemberId(roomId, userId))
                .build();
    }
}
