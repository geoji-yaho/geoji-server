package com.ttegeoji.backend.jobs;

/**
 * ai.jobs kind 4종과 INSERT 규약(10 §3 표). AI 저장소 workers/dispatch.py JOB_ROUTES 와 같은 값이다.
 * RETAIN 은 event_type 이 둘(sentence.finalized·comment.approved)이라 event_type 은 kind 가 아니라 호출 지점이 고른다.
 */
public enum JobKind {
    PREPARE(30, 2, null),
    SENTENCE(100, 2, 10),
    RETAIN(10, 5, null),
    TEXT_RETRY(50, 1, 20);

    public static final String EVENT_POST_CREATED = "post.created";
    public static final String EVENT_VERDICT_CONFIRMED = "verdict.confirmed";
    public static final String EVENT_SENTENCE_FINALIZED = "sentence.finalized";
    public static final String EVENT_VERDICT_TEXT_RETRY = "verdict.text_retry";
    public static final String EVENT_COMMENT_APPROVED = "comment.approved";

    private final int priority;
    private final int maxAttempts;
    // null 이면 deadline_at NULL. 값이 있으면 INSERT 시각(DB now()) + 이 초
    private final Integer deadlineAfterSeconds;

    JobKind(int priority, int maxAttempts, Integer deadlineAfterSeconds) {
        this.priority = priority;
        this.maxAttempts = maxAttempts;
        this.deadlineAfterSeconds = deadlineAfterSeconds;
    }

    public int priority() {
        return priority;
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    public Integer deadlineAfterSeconds() {
        return deadlineAfterSeconds;
    }
}
