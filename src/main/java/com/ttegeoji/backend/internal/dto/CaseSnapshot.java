package com.ttegeoji.backend.internal.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * 10 §4.1 snapshot 응답. 정본은 AI 저장소 contracts/case-snapshot-v1.schema.json. 평면이다(9/11) — post{…} 로 감싸지 않는다.
 * 워커는 알 수 없는 키를 거부하고 required 키가 빠져도 거부하므로, null 인 키도 늘 직렬화한다(전역 Jackson 설정과 무관하게).
 * verdict_final·comment 는 스키마상 선택 키지만 null 로 보내도 허용된다.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record CaseSnapshot(
        @JsonProperty("schema_version") int schemaVersion,
        @JsonProperty("post_id") String postId,
        @JsonProperty("author_id") String authorId,
        @JsonProperty("post_version") int postVersion,
        @JsonProperty("item") String item,
        @JsonProperty("reason") String reason,
        @JsonProperty("amount_krw") int amountKrw,
        @JsonProperty("category") String category,
        @JsonProperty("post_type") String postType,
        @JsonProperty("created_at") String createdAt,
        @JsonProperty("audience") Audience audience,
        @JsonProperty("privacy_versions") List<PrivacyVersion> privacyVersions,
        @JsonProperty("room_snapshots") List<RoomSnapshot> roomSnapshots,
        // IntakeResult 는 submissions.intake_result jsonb 를 그대로 싣는다
        @JsonProperty("intake_result") Map<String, Object> intakeResult,
        @JsonProperty("jury") JurySnapshot jury,
        @JsonProperty("verdict_final") VerdictFinal verdictFinal,
        @JsonProperty("comment") CommentSnapshot comment) {

    public static final int SCHEMA_VERSION = 1;
}
