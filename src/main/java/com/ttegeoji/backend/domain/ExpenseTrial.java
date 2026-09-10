package com.ttegeoji.backend.domain;

import com.ttegeoji.backend.domain.enums.VerdictType;
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
@Table(name = "expense_trials")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExpenseTrial {

    @Id
    @UuidGenerator
    private UUID id;

    @Column(name = "room_id", nullable = false)
    private UUID roomId;

    @Column(name = "expense_id", nullable = false)
    private UUID expenseId;

    @Column(name = "voting_deadline", nullable = false)
    private OffsetDateTime votingDeadline;

    // null = 투표 진행 중
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(columnDefinition = "verdict")
    private VerdictType verdict;

    @Column(name = "verdict_text")
    private String verdictText;

    // 유죄일 때만 채워진다 (무지출 형 집행 일수)
    @Column(name = "sentence_days")
    private Integer sentenceDays;

    @Column(name = "sentence_started_at")
    private OffsetDateTime sentenceStartedAt;

    @Column(name = "sentence_ended_at")
    private OffsetDateTime sentenceEndedAt;

    @Column(name = "judged_at")
    private OffsetDateTime judgedAt;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
