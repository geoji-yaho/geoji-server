package com.ttegeoji.backend.verdictview.dto;

/**
 * 10 §9 verdict-view-v1 을 camelCase 로 낸 것(사용자 9/15). null 도 키를 낸다.
 * juryStatus 는 투표 중(verdicts 행 없음)이면 null 이다 — 스키마 JuryStatus 에 null 이 없어 AI 파트에 회신한다.
 */
public record VerdictViewResponse(
        int schemaVersion,
        String postId,
        String juryStatus,
        String sentenceStatus,
        String textStatus,
        long textVersion,
        VerdictTextView view,
        int pollAfterMs
) {
}
