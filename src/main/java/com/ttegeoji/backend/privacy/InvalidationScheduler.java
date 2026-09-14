package com.ttegeoji.backend.privacy;

import com.ttegeoji.backend.privacy.InvalidationQueries.AffectedVerdictText;
import com.ttegeoji.backend.privacy.InvalidationQueries.FailureRecorded;
import com.ttegeoji.backend.privacy.InvalidationQueries.PendingInvalidation;
import com.ttegeoji.backend.privacy.InvalidationQueries.VerdictForTemplate;
import com.ttegeoji.backend.util.Json;
import com.ttegeoji.backend.verdict.GenerationQueries;
import com.ttegeoji.backend.verdict.TemplateCatalog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

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

    // VerdictFallbackService 와 같다. 템플릿 문장은 근거를 인용하지 않는다
    private static final String TEMPLATE_STATEMENT_KIND = "opinion";

    private final InvalidationQueries queries;
    private final TemplateCatalog templateCatalog;
    private final GenerationQueries generationQueries;
    private final TransactionTemplate tx;

    public InvalidationScheduler(InvalidationQueries queries, TemplateCatalog templateCatalog,
                                 GenerationQueries generationQueries, PlatformTransactionManager transactionManager) {
        this.queries = queries;
        this.templateCatalog = templateCatalog;
        this.generationQueries = generationQueries;
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
     * text_evidence_refs 로 영향받은 verdict_texts 를 템플릿으로 바꾼다(10 §8 4문단). 형량 FINAL·sentencing_reason 은 건드리지 않는다.
     * verdicts.text_version +1, text_status TEMPLATE_READY. TEXT_RETRY 는 새로 예약하지 않는다(사용자 9/15).
     * 호출자 트랜잭션 안에서 invalidate_scope.sql 뒤에 부른다. 잠금은 privacy_invalidations 행 → verdict 행(id 오름차순).
     */
    void convertAffectedTextsToTemplate(PendingInvalidation job) {
        List<AffectedVerdictText> found = queries.findAffectedVerdictTexts(job.sourceType(), job.sourceId());
        if (found.isEmpty()) {
            return;
        }
        List<UUID> verdictIds = found.stream().map(AffectedVerdictText::verdictId).distinct().toList();
        List<VerdictForTemplate> verdicts = queries.lockVerdictsForTemplate(verdictIds);
        // 잠그기 전에 finalize 가 문구를 바꿨을 수 있어 잠근 뒤 다시 찾는다
        Map<UUID, List<AffectedVerdictText>> affected = queries.findAffectedVerdictTexts(job.sourceType(), job.sourceId())
                .stream().collect(Collectors.groupingBy(AffectedVerdictText::verdictId));
        for (VerdictForTemplate verdict : verdicts) {
            List<AffectedVerdictText> texts = affected.get(verdict.id());
            if (texts == null) {
                continue;
            }
            GenerationQueries.JuryCounts counts = generationQueries.juryCounts(verdict.postId());
            TemplateCatalog.Rendered rendered = templateCatalog.render(verdict.juryResult(), counts.juryCount(),
                    counts.guiltyCount(), verdict.sentence());
            String statement = Json.write(rendered.statement().stream()
                    .map(text -> Map.of("text", text, "kind", TEMPLATE_STATEMENT_KIND, "evidence_labels", List.of()))
                    .toList());
            // 인용한 강도 행만 새 text_version. 옛 version 의 text_evidence_refs 는 DELETE 권한이 없어 남지만
            // 영향 조회가 현재 version 만 보므로 재실행해도 다시 잡히지 않는다
            long textVersion = verdict.textVersion() + 1;
            for (AffectedVerdictText text : texts) {
                queries.replaceTextWithTemplate(text.id(), rendered.headline(), statement, textVersion);
            }
            queries.markVerdictTemplateReady(verdict.id(), textVersion);
        }
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
