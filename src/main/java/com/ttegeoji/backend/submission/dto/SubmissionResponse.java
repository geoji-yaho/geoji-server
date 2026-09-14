package com.ttegeoji.backend.submission.dto;

import com.ttegeoji.backend.domain.enums.SubmissionStatus;

import java.util.Map;
import java.util.UUID;

/**
 * 제출·완료 응답(10 §9). 공개 API 라 camelCase. revision 은 다음 complete 에 그대로 돌려보낼 값(현재 payload_hash).
 * intakeResult 는 intake-v1 IntakeResult 를 camelCase 로 옮긴 모양, postId 는 COMPLETED 일 때만.
 */
public record SubmissionResponse(UUID submissionId, SubmissionStatus status, String revision,
                                 Map<String, Object> intakeResult, UUID postId) {
}
