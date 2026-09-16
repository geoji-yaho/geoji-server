package com.ttegeoji.backend.jobs;

/**
 * ai.jobs kind 4종과 INSERT 규약(10 §3 표). AI 저장소 workers/dispatch.py JOB_ROUTES 와 같은 값이다.
 * RETAIN 은 event_type 이 둘(sentence.finalized·comment.approved)이라 event_type 은 kind 가 아니라 호출 지점이 고른다.
 */
public enum JobKind {
    PREPARE(30, 2, null),
    // 10 §3 D-24 는 10초지만 운영에서 그 예산으로는 AI 문구가 구조적으로 안 나온다(9/16, AI 파트 회신).
    // 워커의 노드 상한이 min(노드 상한, 남은 마감)이라, 마감이 짧으면 뒤쪽 노드부터 차례로 잘린다.
    // 25초로 올렸더니 sentencing·writer 는 살았지만 마지막 evaluator 가 또 잘렸다 —
    // job 실측이 예산 25초 중 23.7초였다(노드 합 12.4초 + 노드 밖 오버헤드 약 11초).
    // 45초면 노드가 각자 상한(sentencing 8 · writer 8 · evaluator 10)을 다 받고 finalize 여유도 남는다.
    SENTENCE(100, 2, 45),
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
