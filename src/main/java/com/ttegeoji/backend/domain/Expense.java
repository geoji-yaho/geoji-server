package com.ttegeoji.backend.domain;

import com.ttegeoji.backend.domain.enums.ExpenseSource;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "expenses")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Expense {

    @Id
    @UuidGenerator
    private UUID id;

    // 방 소유가 아니라 유저 소유다. 이 유저가 속한 모든 방의 그리드에 같은 지출이 보여야 하므로
    // 특정 방에 고정하지 않는다 — 노출 대상은 room_members 조인으로 그때그때 계산한다.
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private Integer amount;

    // AI 자동 추론 결과. 사용자가 직접 고르지 않으므로 enum으로 제약하지 않는다.
    private String category;

    private String memo;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "source", nullable = false, columnDefinition = "expense_source")
    private ExpenseSource source;

    @Column(name = "spent_at", nullable = false)
    private OffsetDateTime spentAt;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
