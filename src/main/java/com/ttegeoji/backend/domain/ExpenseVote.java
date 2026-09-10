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
@Table(name = "expense_votes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExpenseVote {

    @Id
    @UuidGenerator
    private UUID id;

    @Column(name = "trial_id", nullable = false)
    private UUID trialId;

    @Column(name = "voter_user_id", nullable = false)
    private UUID voterUserId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "verdict")
    private VerdictType verdict;

    @Column(nullable = false)
    private String reason;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
