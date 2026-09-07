package com.ttegeoji.backend.domain;

import com.ttegeoji.backend.domain.enums.AwardType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "weekly_awards")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WeeklyAward {

    @Id
    @UuidGenerator
    private UUID id;

    @Column(name = "room_id", nullable = false)
    private UUID roomId;

    @Column(name = "week_start", nullable = false)
    private LocalDate weekStart;

    @Column(name = "week_end", nullable = false)
    private LocalDate weekEnd;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "award_type", nullable = false, columnDefinition = "award_type")
    private AwardType awardType;

    // 고정 상은 "거지왕"/"탕진왕", invented는 LLM이 발명한 이름
    @Column(nullable = false)
    private String title;

    @Column(name = "winner_user_id")
    private UUID winnerUserId;

    // 수상평. LLM 생성이지만 금액·횟수는 statsSnapshot에서만 가져온다 (할루시네이션 방지)
    private String description;

    // 상 발명의 근거가 된 통계 후보 데이터. 이미 직렬화된 JSON 문자열이어야 한다 (Hibernate JSON 매핑 규칙).
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "stats_snapshot", nullable = false, columnDefinition = "jsonb")
    private String statsSnapshot;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
