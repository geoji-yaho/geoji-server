package com.ttegeoji.backend.comment;

import com.ttegeoji.backend.comment.CommentQueries.RetainCandidate;
import com.ttegeoji.backend.jobs.JobEnqueuer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * 10 §3 판결 확정 전에 달린 댓글을 확정 뒤 RETAIN comment.approved 로 넣는다(사용자 9/15).
 * 확정 지점이 finalize·폴백·watchdog 여러 곳이라 그 경로를 고치지 않고 스캔으로 잡는다. 중복은 dedupe 가 막는다.
 * 댓글 삭제와 같은 행 잠금이라, 삭제가 먼저 커밋된 댓글은 넣지 않는다.
 * test 프로필에서는 빈으로 띄우지 않는다(TextRetryScheduler 와 같은 이유). 테스트는 인스턴스를 직접 만든다.
 */
@Slf4j
@Component
@Profile("!test")
public class CommentRetainScheduler {

    static final int BATCH = 50;

    private final CommentQueries queries;
    private final JobEnqueuer jobEnqueuer;
    private final TransactionTemplate tx;

    public CommentRetainScheduler(CommentQueries queries, JobEnqueuer jobEnqueuer,
                                  PlatformTransactionManager transactionManager) {
        this.queries = queries;
        this.jobEnqueuer = jobEnqueuer;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelay = 5000)
    public void runCycle() {
        try {
            retainJudged();
        } catch (RuntimeException e) {
            log.error("10 §3 댓글 RETAIN 스캔 실패", e);
        }
    }

    /** @return 이번 주기에 retained_at 을 채운 댓글 수 */
    int retainJudged() {
        Integer done = tx.execute(status -> {
            List<RetainCandidate> candidates = queries.lockRetainCandidates(BATCH);
            for (RetainCandidate candidate : candidates) {
                jobEnqueuer.enqueueRetainComment(candidate.id().toString(), candidate.version());
                queries.markRetained(candidate.id());
            }
            return candidates.size();
        });
        if (done != null && done > 0) {
            log.info("10 §3 댓글 RETAIN {}건", done);
        }
        return done == null ? 0 : done;
    }
}
