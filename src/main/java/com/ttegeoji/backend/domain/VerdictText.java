package com.ttegeoji.backend.domain;

import com.ttegeoji.backend.domain.enums.ContentSource;
import com.ttegeoji.backend.domain.enums.SpiceLevel;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

// 10 §2 verdict_texts. 강도별 1행, UNIQUE(verdict_id, intensity)
@Entity
@Table(name = "verdict_texts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VerdictText {

    @Id
    @UuidGenerator
    private UUID id;

    @Column(name = "verdict_id", nullable = false)
    private UUID verdictId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "spice_level")
    private SpiceLevel intensity;

    @Column(nullable = false)
    private String headline;

    // 문장 배열 [{text, kind, evidence_labels}]. 직렬화된 JSON 문자열
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String statement;

    // AI 또는 TEMPLATE
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ContentSource source;

    @Column(name = "text_version", nullable = false)
    private Long textVersion;

    @Column(name = "dossier_id")
    private UUID dossierId;

    // 저장 당시 [{scope_key, epoch}]. 조회 때 현재 epoch 와 비교(10 §8)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "privacy_epoch_snapshot", columnDefinition = "jsonb")
    private String privacyEpochSnapshot;
}
