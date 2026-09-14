package com.ttegeoji.backend.submission.dto;

import com.ttegeoji.backend.domain.enums.SubmissionStatus;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import java.util.Map;
import java.util.UUID;

/**
 * 제출·완료 응답(10 §9). revision 은 다음 complete 에 그대로 돌려보낼 값(현재 payload_hash).
 * intake_result 는 intake-v1 IntakeResult 모양, post_id 는 COMPLETED 일 때만.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SubmissionResponse(UUID submissionId, SubmissionStatus status, String revision,
                                 Map<String, Object> intakeResult, UUID postId) {
}
