package com.ttegeoji.backend.jobs;

/**
 * ai.jobs kind 4종과 INSERT 규약(10 §3 표). AI 저장소 workers/dispatch.py JOB_ROUTES 와 같은 값이다.
 * RETAIN 은 event_type 이 둘(sentence.finalized·comment.approved)이라 event_type 은 kind 가 아니라 호출 지점이 고른다.
 */
public enum JobKind {
    PREPARE(30, 2, null),
    // 10 §3 D-24 는 10초지만 운영에서 그 예산으로는 AI 문구가 구조적으로 안 나온다(9/16, AI 파트 회신).
    // 워커의 노드 상한이 min(노드 상한, 남은 마감)이라 마감이 짧으면 뒤쪽 노드부터 차례로 잘린다.
    // 10 → 25 → 45 로 올리며 sentencing·writer·evaluator 를 차례로 살렸고, 45초에서는 검수 뒤
    // writer_repair 가 "남은 ≥ 5초" 문턱을 못 넘어 재작성을 건너뛰었다(4ms 통과 → 검증 실패).
    // 실측 합: begin 0.9 + prep 0.5 + sentencing 8.6 + writer 5.9 + evaluator 13.9 + 재작성 6
    //        + 검수 2회차 14 + finalize 1.5 + 노드 밖 오버헤드 11 ≈ 62초.
    // 90 은 상한이지 대기 시간이 아니다. 검수가 1회차에 통과하면 40초대에 끝난다.
    SENTENCE(100, 2, 90),
    RETAIN(10, 5, null),
    // 10 §3 은 20초지만 TEXT_RETRY 는 SENTENCE 와 같은 writer·evaluator 를 다시 돌린다.
    // 운영 실측(9/17 워커 로그): writer 3.9~5.9초 + evaluator 11.6~13.9초 + finalize 1.5초 ≈ 19~21초라
    // 대기 0 이어도 20초 안에 끝날 수 없다. 실제로 writer 가 4ms 만에 통과하고(예산 0)
    // join 이 DEADLINE_EXCEEDED 로 떨어져 재시도가 한 번도 성공한 적이 없다.
    // SENTENCE 와 같은 그래프이므로 같은 90 을 준다. 상한이지 대기 시간이 아니다
    TEXT_RETRY(50, 1, 90);

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
