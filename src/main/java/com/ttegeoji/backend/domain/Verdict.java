package com.ttegeoji.backend.domain;

import com.ttegeoji.backend.domain.enums.ContentSource;
import com.ttegeoji.backend.domain.enums.Sentence;
import com.ttegeoji.backend.domain.enums.SentenceStatus;
import com.ttegeoji.backend.domain.enums.SpiceLevel;
import com.ttegeoji.backend.domain.enums.TextStatus;
import com.ttegeoji.backend.domain.enums.VerdictType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

// 10 §2 verdicts. **방마다 1건**(9/16 사용자 결정, 10 §2·§5 이탈). 잠글 때는 privacy scope 행 뒤, job 행 앞(10 §2 잠금 순서)
@Entity
@Table(name = "verdicts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Verdict {

    @Id
    @UuidGenerator
    private UUID id;

    @Column(name = "post_id", nullable = false)
    private UUID postId;

    /**
     * 이 판결이 난 방. 게시물은 여러 방에 공유되지만 재판은 방마다 따로 한다.
     * {@code null} 은 방별 재판 이전에 만들어진 옛 합산 판결이다(0017 마이그레이션).
     */
    @Column(name = "room_id")
    private UUID roomId;

    // 업무 버전(SENTENCE dedupe·aggregate_version). JPA 낙관적 잠금 @Version 이 아니다
    @Builder.Default
    @Column(name = "verdict_version", nullable = false)
    private Integer verdictVersion = 1;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "jury_result", nullable = false, columnDefinition = "verdict")
    private VerdictType juryResult;

    // {allowed_sentences[{code, rank}], fallback_sentence, reason_required, version}. 직렬화된 JSON 문자열
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "policy_snapshot", nullable = false, columnDefinition = "jsonb")
    private String policySnapshot;

    @Column(name = "confirmed_at", nullable = false)
    private OffsetDateTime confirmedAt;

    // D-24: SENTENCE INSERT 전에는 null
    @Column(name = "deadline_at")
    private OffsetDateTime deadlineAt;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "sentence_status", nullable = false)
    private SentenceStatus sentenceStatus = SentenceStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(columnDefinition = "sentence")
    private Sentence sentence;

    // AI 또는 RULE
    @Enumerated(EnumType.STRING)
    @Column(name = "sentence_source")
    private ContentSource sentenceSource;

    @Column(name = "sentencing_reason")
    private String sentencingReason;

    // AI 또는 TEMPLATE
    @Enumerated(EnumType.STRING)
    @Column(name = "reason_source")
    private ContentSource reasonSource;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "text_status", nullable = false)
    private TextStatus textStatus = TextStatus.PENDING;

    @Builder.Default
    @Column(name = "text_version", nullable = false)
    private Long textVersion = 0L;

    @Column(name = "active_generation_id")
    private UUID activeGenerationId;

    @Column(name = "active_job_id")
    private UUID activeJobId;

    @Builder.Default
    @Column(name = "retry_round", nullable = false)
    private Integer retryRound = 0;

    @Column(name = "pending_retry_at")
    private OffsetDateTime pendingRetryAt;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "applied_intensity", columnDefinition = "spice_level")
    private SpiceLevel appliedIntensity;

    @Column(name = "meme_image_id")
    private UUID memeImageId;

    // §4.1 jury 스냅샷 출처. 공유 방 강도 문자열 배열(예: ["mild","hell"])
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "target_intensities", nullable = false, columnDefinition = "jsonb")
    private String targetIntensities;

    // 표 최다 방 강도(동률은 방 생성일 이른 방)
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "default_intensity", nullable = false, columnDefinition = "spice_level")
    private SpiceLevel defaultIntensity;

    // §4.6 generation-failed 재전송 판정
    @Column(name = "last_failed_generation_id")
    private UUID lastFailedGenerationId;

    @Column(name = "last_failed_code")
    private String lastFailedCode;
}
