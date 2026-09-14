package com.ttegeoji.backend.domain;

import com.ttegeoji.backend.domain.enums.IntakeSource;
import com.ttegeoji.backend.domain.enums.IntakeStatus;
import com.ttegeoji.backend.domain.enums.PostType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

// 10 §2 posts. 기존 expenses 와 별개인 재판 흐름 게시물이다(9/14 결정). 공유 방은 post_rooms.
@Entity
@Table(name = "posts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Post {

    @Id
    @UuidGenerator
    private UUID id;

    @Column(name = "author_id", nullable = false)
    private UUID authorId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "post_type", nullable = false, columnDefinition = "post_type")
    private PostType postType;

    @Column(name = "amount_krw", nullable = false)
    private Integer amountKrw;

    // 카테고리 11종 문자열 그대로(10 §15.2). 값 제한은 DB CHECK
    @Column(nullable = false)
    private String category;

    // 무엇을. 1~30자
    @Column(nullable = false)
    private String item;

    // 사유. 선택, 200자 이하
    private String reason;

    // PREPARE dedupe·aggregate_version 에 들어가므로 1부터(10 §3)
    @Builder.Default
    @Column(nullable = false)
    private Integer version = 1;

    @Builder.Default
    @Column(name = "audience_version", nullable = false)
    private Integer audienceVersion = 1;

    @Enumerated(EnumType.STRING)
    @Column(name = "intake_status", nullable = false)
    private IntakeStatus intakeStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "intake_source", nullable = false)
    private IntakeSource intakeSource;

    @Column(name = "submission_id")
    private UUID submissionId;

    // 생성 시각 + 공유 방 vote_deadline_minutes 최소값(9/14 결정)
    @Column(name = "vote_deadline_at", nullable = false)
    private OffsetDateTime voteDeadlineAt;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
