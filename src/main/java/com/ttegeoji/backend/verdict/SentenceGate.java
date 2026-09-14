package com.ttegeoji.backend.verdict;

import com.ttegeoji.backend.jobs.EnqueuedJob;
import com.ttegeoji.backend.jobs.JobEnqueuer;
import com.ttegeoji.backend.jobs.JobQueries;
import com.ttegeoji.backend.verdict.JuryQueries.GateRow;
import com.ttegeoji.backend.verdict.JuryQueries.PendingVerdict;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 10 §3 D-24 SENTENCE 게이트(9/14 채택). 같은 post 의 PREPARE 가 QUEUED·RUNNING 이면 보류하고,
 * PREPARE 가 끝났거나(없음 포함) confirmed_at + 30s 가 지났으면 SENTENCE 를 넣는다.
 * job·verdict deadline_at 은 INSERT 시각(DB now()) + 10s. 보류 중엔 deadline_at 이 NULL 이라 watchdog 대상이 아니다(10 §6).
 * 잠금 순서(10 §2): verdict 행 → job 행. 게시물이 보류 중 삭제돼도 D-26 이 PREPARE 를 CANCELLED 로 바꿔 다음 주기에 넣는다
 * (그 SENTENCE 는 무효화가 끈다, 가짜 백엔드와 같다).
 */
@Component
@RequiredArgsConstructor
public class SentenceGate {

    private final JuryQueries juryQueries;
    private final JobQueries jobQueries;
    private final JobEnqueuer jobEnqueuer;

    /**
     * 평결 확정 트랜잭션에서는 그 트랜잭션에 합류하고, 스케줄러에서는 verdict 하나당 트랜잭션 하나다.
     *
     * @return 이번 호출로 SENTENCE 를 넣었으면 true. 보류·이미 넣음·대상 아님이면 false
     */
    @Transactional
    public boolean tryInsert(PendingVerdict verdict) {
        GateRow row = juryQueries.lockVerdictForGate(verdict.verdictId()).orElse(null);
        if (row == null
                || !"PENDING".equals(row.sentenceStatus())
                || row.deadlineAt() != null
                || "dismissed".equals(row.juryResult())
                || !row.policyValid()) {
            return false;
        }
        if (jobQueries.isPrepareActive(row.postId().toString()) && !row.waitOver()) {
            return false;
        }
        EnqueuedJob job = jobEnqueuer.enqueueSentence(
                row.verdictId().toString(), row.verdictVersion(), row.postId().toString());
        juryQueries.setDeadline(row.verdictId(), job.deadlineAt());
        return true;
    }
}
