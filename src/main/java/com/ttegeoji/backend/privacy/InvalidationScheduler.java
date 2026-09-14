package com.ttegeoji.backend.privacy;

import com.ttegeoji.backend.privacy.InvalidationQueries.AffectedVerdictText;
import com.ttegeoji.backend.privacy.InvalidationQueries.FailureRecorded;
import com.ttegeoji.backend.privacy.InvalidationQueries.PendingInvalidation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * 10 §8 무효화 작업 비동기 처리. PENDING 행마다 한 트랜잭션: 행 잠금 → invalidate_scope.sql → 영향 verdict_texts 템플릿 전환 → DONE.
 * 실패하면 롤백 뒤 새 트랜잭션에서 attempts +1, 5회째 FAILED. 읽기 차단은 epoch 로 이미 됐으므로 초 단위 지연이면 충분하다.
 * privacy_invalidations 행 잠금은 10 §2 순서 밖이지만 삭제 트랜잭션은 이 표에 INSERT 만 해 순환이 생기지 않는다.
 */
@Slf4j
@Component
public class InvalidationScheduler {

    static final int BATCH_SIZE = 50;
    static final int MAX_ATTEMPTS = 5;

    private final InvalidationQueries queries;
    private final TransactionTemplate tx;

    public InvalidationScheduler(InvalidationQueries queries, PlatformTransactionManager transactionManager) {
        this.queries = queries;
        // 호출자 트랜잭션에 섞이지 않게 행마다 새 트랜잭션
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** @return 이번 실행에서 집어 처리(성공·실패 포함)한 행 수 */
    @Scheduled(fixedDelay = 5000)
    public int runOnce() {
        List<Long> ids = tx.execute(s -> queries.claimPendingIds(BATCH_SIZE));
        int handled = 0;
        for (Long id : ids == null ? List.<Long>of() : ids) {
            if (processOne(id)) {
                handled++;
            }
        }
        return handled;
    }

    /** @return 이 호출이 행을 집었으면 true. 이미 끝났거나 다른 인스턴스가 잡고 있으면 false */
    public boolean processOne(long id) {
        try {
            return Boolean.TRUE.equals(tx.execute(s -> {
                Optional<PendingInvalidation> row = queries.lockPending(id);
                if (row.isEmpty()) {
                    return false;
                }
                PendingInvalidation job = row.get();
                queries.runInvalidateScope(job.sourceType(), job.sourceId(), job.scopeKey());
                convertAffectedTextsToTemplate(job);
                queries.markDone(job.id());
                return true;
            }));
        } catch (RuntimeException e) {
            recordFailure(id, e);
            return true;
        }
    }

    /**
     * text_evidence_refs 로 영향받은 verdict_texts 를 템플릿으로 바꾼다(10 §8 4문단). 형량 FINAL 은 건드리지 않는다.
     * 호출자 트랜잭션 안에서 invalidate_scope.sql 뒤에 부른다.
     */
    void convertAffectedTextsToTemplate(PendingInvalidation job) {
        List<AffectedVerdictText> affected = queries.findAffectedVerdictTexts(job.sourceType(), job.sourceId());
        if (affected.isEmpty()) {
            return;
        }
        // TODO 게이트 A 답 대기: 템플릿 전환 쓰기. statement 원소 모양(kind·evidence_labels·문장 분할),
        //  verdicts.text_version +1 여부, text_status 전환, sentencing_reason·reason_source 치환,
        //  text_evidence_refs 처리, 전환 행의 privacy_epoch_snapshot·dossier_id 가 10 에 없다.
        //  verdict 행을 잠근다면 privacy_invalidations 행 다음, verdict_texts 갱신 전에 잠근다(10 §2).
    }

    private void recordFailure(long id, RuntimeException e) {
        String lastError = describe(e);
        Optional<FailureRecorded> recorded = tx.execute(s -> queries.recordFailure(id, lastError, MAX_ATTEMPTS));
        if (recorded != null && recorded.isPresent() && "FAILED".equals(recorded.get().status())) {
            FailureRecorded r = recorded.get();
            log.warn("privacy invalidation FAILED id={} scope_key={} attempts={}", r.id(), r.scopeKey(), r.attempts());
        }
    }

    /** 원문(메시지)은 삭제된 내용을 담을 수 있어 남기지 않는다 */
    static String describe(Throwable e) {
        Throwable cause = NestedExceptionUtils.getMostSpecificCause(e);
        String sqlState = null;
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                sqlState = sql.getSQLState();
                break;
            }
        }
        String name = (cause == null ? e : cause).getClass().getName();
        return sqlState == null ? name : name + " sqlstate=" + sqlState;
    }
}
