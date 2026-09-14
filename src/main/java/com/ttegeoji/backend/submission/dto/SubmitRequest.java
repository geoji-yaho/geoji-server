package com.ttegeoji.backend.submission.dto;

import com.ttegeoji.backend.domain.enums.PostType;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import java.util.List;
import java.util.UUID;

/** POST /api/post-submissions 요청(10 §9). 검증은 SubmissionPayload.normalize 가 한다(400 메시지를 한 곳에서). */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SubmitRequest(PostType postType, Integer amountKrw, String category, String item, String reason,
                            List<UUID> roomIds) {
}
