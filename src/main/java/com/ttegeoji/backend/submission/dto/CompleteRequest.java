package com.ttegeoji.backend.submission.dto;

import com.ttegeoji.backend.domain.enums.PostType;

import java.util.List;
import java.util.UUID;

/**
 * POST /api/post-submissions/{id}/complete 요청(10 §9). 최종 값 전체와 마지막 응답의 revision 을 보낸다.
 * REVISE 는 고친 값으로 FINAL_CHECK 1회, PROCEED 는 저장된 값 그대로 등록.
 */
public record CompleteRequest(Action action, String revision, PostType postType, Integer amountKrw, String category,
                              String item, String reason, List<UUID> roomIds) {

    public enum Action {
        REVISE, PROCEED
    }
}
