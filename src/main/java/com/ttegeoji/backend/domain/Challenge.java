package com.ttegeoji.backend.domain;

import com.ttegeoji.backend.domain.enums.ChallengeStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "challenges")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Challenge {

    @Id
    @UuidGenerator
    private UUID id;

    @Column(name = "room_id", nullable = false)
    private UUID roomId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "week_start", nullable = false)
    private LocalDate weekStart;

    // [{label, saved_amount}] 이미 직렬화된 JSON 문자열. 금액·횟수는 통계 계산 결과를 슬롯으로 주입한 것이라 LLM이 만들지 않는다.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String items;

    // LLM이 items를 문장으로 엮은 결과물
    private String narrative;

    @Column(name = "target_amount", nullable = false)
    private Integer targetAmount;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "challenge_status")
    private ChallengeStatus status;

    @Column(name = "accepted_at")
    private OffsetDateTime acceptedAt;

    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
