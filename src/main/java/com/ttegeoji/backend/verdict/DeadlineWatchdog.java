package com.ttegeoji.backend.verdict;

import com.ttegeoji.backend.domain.Verdict;
import com.ttegeoji.backend.domain.enums.SentenceStatus;
import com.ttegeoji.backend.domain.enums.TextStatus;
import com.ttegeoji.backend.jobs.JobQueries;
import com.ttegeoji.backend.privacy.PrivacyEpochRepository;
import com.ttegeoji.backend.repository.VerdictRepository;
import com.ttegeoji.backend.verdict.GenerationQueries.LockedJob;
import com.ttegeoji.backend.verdict.SchedulerQueries.OverdueVerdict;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 10 §6 deadline watchdog. 250ms 마다 마감 지난 PENDING verdict 를 verdict 하나당 한 트랜잭션으로 폴백한다.
 * 1 privacy scope → verdict(SKIP LOCKED) → 끝나지 않은 SENTENCE job 잠금, 2 이미 확정이면 skip,
 * 3·5 형량 fallback FINAL/RULE·문구 TEMPLATE·RETAIN·TEXT_RETRY round 1 예약(VerdictFallbackService),
 * 4 active 비우고 이전 job CANCELLED(9/14 사용자 결정), 6 늦은 워커 응답은 finalize·generation-failed 가 STALE 로 거부.
 * 여러 인스턴스여도 잠금과 PENDING 재확인으로 한 번만 확정한다. 엄밀한 10,000ms 보장은 아니다.
 * test 프로필에서는 빈으로 띄우지 않는다. 다른 테스트가 커밋해 둔 마감 지난 PENDING 을 백그라운드에서 바꾸지 않게 하고,
 * 테스트는 인스턴스를 직접 만들어 주기를 부른다.
 */
@Slf4j
@Component
@Profile("!test")
public class DeadlineWatchdog {

    private final SchedulerQueries schedulerQueries;
    private final GenerationQueries generationQueries;
    private final PrivacyEpochRepository privacyEpochRepository;
    private final VerdictRepository verdictRepository;
    private final VerdictFallbackService fallbackService;
    private final JobQueries jobQueries;
    private final TransactionTemplate tx;

    public DeadlineWatchdog(SchedulerQueries schedulerQueries, GenerationQueries generationQueries,
                            PrivacyEpochRepository privacyEpochRepository, VerdictRepository verdictRepository,
                            VerdictFallbackService fallbackService, JobQueries jobQueries,
                            PlatformTransactionManager transactionManager) {
        this.schedulerQueries = schedulerQueries;
        this.generationQueries = generationQueries;
        this.privacyEpochRepository = privacyEpochRepository;
        this.verdictRepository = verdictRepository;
        this.fallbackService = fallbackService;
        this.jobQueries = jobQueries;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** @return 이번 주기에 폴백한 verdict 수 */
    @Scheduled(fixedDelay = 250)
    public int runCycle() {
        int applied = 0;
        List<OverdueVerdict> overdue;
        try {
            overdue = schedulerQueries.overduePendingVerdicts();
        } catch (RuntimeException e) {
            log.error("10 §6 마감 초과 스캔 실패", e);
            return 0;
        }
        for (OverdueVerdict v : overdue) {
            try {
                if (fallback(v.verdictId(), v.postId())) {
                    applied++;
                }
            } catch (RuntimeException e) {
                log.error("10 §6 watchdog 폴백 실패 verdict={}", v.verdictId(), e);
            }
        }
        return applied;
    }

    /** @return 이번 호출이 폴백을 확정했으면 true */
    boolean fallback(UUID verdictId, UUID postId) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            // 1. privacy scope → verdict → job
            privacyEpochRepository.lockAndRead(generationQueries.scopeKeys(postId));
            if (!schedulerQueries.lockOverduePending(verdictId)) {
                return false;
            }
            Verdict verdict = verdictRepository.findByIdForUpdate(verdictId).orElse(null);
            // 2. 이미 AI_READY 또는 FINAL + TEMPLATE_READY
            if (verdict == null || alreadyFixed(verdict)) {
                return false;
            }
            List<UUID> jobIds = new ArrayList<>(schedulerQueries.openSentenceJobIds(verdictId));
            if (verdict.getActiveJobId() != null) {
                jobIds.add(verdict.getActiveJobId());
            }
            List<LockedJob> jobs = generationQueries.lockJobs(jobIds);

            // 3·5. 폴백 + RETAIN + round 1 예약. active_* 도 여기서 비운다
            if (!fallbackService.applyFallback(verdict, true, null)) {
                return false;
            }
            // 4. 이전 job CANCELLED. 이미 끝난 job 은 그대로
            for (LockedJob job : jobs) {
                jobQueries.cancel(job.id());
            }
            log.info("10 §6 watchdog 폴백 verdict={} cancelled_jobs={}", verdictId, jobs.stream().map(LockedJob::id).toList());
            return true;
        }));
    }

    private static boolean alreadyFixed(Verdict verdict) {
        // FINAL(+TEMPLATE_READY 포함)은 형량이 이미 확정됐다
        return verdict.getTextStatus() == TextStatus.AI_READY || verdict.getSentenceStatus() != SentenceStatus.PENDING;
    }
}
