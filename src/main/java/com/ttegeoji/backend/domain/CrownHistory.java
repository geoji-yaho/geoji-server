package com.ttegeoji.backend.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "crown_history")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CrownHistory {

    @Id
    @UuidGenerator
    private UUID id;

    @Column(name = "room_id", nullable = false)
    private UUID roomId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "started_at", insertable = false, updatable = false)
    private OffsetDateTime startedAt;

    // null = 현재 재위 중. 방마다 null인 행은 DB의 partial unique index로 최대 1개만 허용된다.
    @Column(name = "ended_at")
    private OffsetDateTime endedAt;

    @Column(name = "dethroned_by_expense_id")
    private UUID dethronedByExpenseId;
}
