package com.ttegeoji.backend.domain;

import com.ttegeoji.backend.domain.enums.SubmissionStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

// 10 §2·§9 submissions. NEW → NEEDS_INPUT → COMPLETED, BLOCKED, EXPIRED
@Entity
@Table(name = "submissions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Submission {

    @Id
    @UuidGenerator
    private UUID id;

    @Column(name = "actor_id", nullable = false)
    private UUID actorId;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SubmissionStatus status = SubmissionStatus.NEW;

    @Column(name = "payload_hash", nullable = false)
    private String payloadHash;

    // 질문은 제출당 1회
    @Builder.Default
    @Column(name = "question_shown", nullable = false)
    private Boolean questionShown = false;

    @Builder.Default
    @Column(name = "final_check_count", nullable = false)
    private Integer finalCheckCount = 0;

    @Column(name = "post_id")
    private UUID postId;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    // 마지막 IntakeResult. 직렬화된 JSON 문자열
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "intake_result", columnDefinition = "jsonb")
    private String intakeResult;
}
