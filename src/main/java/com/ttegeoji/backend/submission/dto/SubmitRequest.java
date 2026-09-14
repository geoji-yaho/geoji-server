package com.ttegeoji.backend.submission.dto;

import com.ttegeoji.backend.domain.enums.PostType;

import java.util.List;
import java.util.UUID;

/** POST /api/post-submissions 요청(10 §9). 검증은 SubmissionPayload.normalize 가 한다(400 메시지를 한 곳에서). */
public record SubmitRequest(PostType postType, Integer amountKrw, String category, String item, String reason,
                            List<UUID> roomIds) {
}
