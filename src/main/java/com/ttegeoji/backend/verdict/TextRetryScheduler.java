package com.ttegeoji.backend.verdict;

import com.ttegeoji.backend.domain.enums.SpiceLevel;
import com.ttegeoji.backend.jobs.EnqueuedJob;
import com.ttegeoji.backend.jobs.JobEnqueuer;
import com.ttegeoji.backend.verdict.SchedulerQueries.DueRetry;
import com.ttegeoji.backend.verdict.SchedulerQueries.ReapedRetry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * 10 §7 재시도 round 스케줄러. 스캔 주기 5분이라 예약 시각보다 최대 한 주기 늦는다.
 * 예약 시각이 지난 verdict 에 TEXT_RETRY 를 넣고(dedupe text-retry:{verdict}:{version}:{round}) 예약을 비운다.
 * reaper 가 FAILED 로 바꾼 TEXT_RETRY 는 다음 round 를 5·10·20분 규칙으로 예약하고, round 3 이면 템플릿 유지 + WARN.
 * test 프로필에서는 빈으로 띄우지 않는다(DeadlineWatchdog 와 같은 이유). 테스트는 인스턴스를 직접 만든다.
 */
@Slf4j
@Component
@Profile("!test")
public class TextRetryScheduler {

    private final SchedulerQueries schedulerQueries;
    private final JobEnqueuer jobEnqueuer;
    private final TransactionTemplate tx;

    public TextRetryScheduler(SchedulerQueries schedulerQueries, JobEnqueuer jobEnqueuer,
                              PlatformTransactionManager transactionManager) {
        this.schedulerQueries = schedulerQueries;
        this.jobEnqueuer = jobEnqueuer;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelay = 300_000)
    public void runCycle() {
        scheduleAfterReap();
        enqueueDue();
    }

    /** @return 이번 주기에 넣은 TEXT_RETRY 수 */
    int enqueueDue() {
        return forEach(safeScan(schedulerQueries::dueRetryVerdictIds), this::enqueue, "TEXT_RETRY INSERT");
    }

    /** @return 이번 주기에 처리한(다음 round 예약 또는 WARN) 회수 건수 */
    int scheduleAfterReap() {
        return forEach(safeScan(schedulerQueries::reapedRetryVerdictIds), this::handleReaped, "회수 뒤 round 예약");
    }

    boolean enqueue(UUID verdictId) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            DueRetry due = schedulerQueries.lockDueRetry(verdictId).orElse(null);
            if (due == null) {
                return false;
            }
            List<SpiceLevel> intensities = schedulerQueries.templateIntensities(verdictId);
            schedulerQueries.clearPendingRetry(verdictId);
            if (intensities.isEmpty()) {
                // TEMPLATE_READY 인데 TEMPLATE 문구가 없으면 다시 쓸 강도가 없다
                log.warn("10 §7 TEMPLATE 문구가 없어 TEXT_RETRY 를 넣지 않음 verdict={} round={}", verdictId, due.round());
                return false;
            }
            EnqueuedJob job = jobEnqueuer.enqueueTextRetry(verdictId.toString(), due.verdictVersion(), due.round(),
                    intensities);
            log.info("10 §7 TEXT_RETRY round {} verdict={} job={} intensities={}", due.round(), verdictId, job.id(),
                    intensities);
            return job.created();
        }));
    }

    boolean handleReaped(UUID verdictId) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            ReapedRetry reaped = schedulerQueries.lockReapedRetry(verdictId).orElse(null);
            if (reaped == null) {
                return false;
            }
            int round = reaped.round();
            if (round < GenerationFailedService.MAX_RETRY_ROUND) {
                int next = round + 1;
                schedulerQueries.scheduleRound(verdictId, next, GenerationFailedService.ROUND_DELAYS.get(next - 1));
                log.info("10 §7 TEXT_RETRY round {} 회수(LEASE_EXPIRED) → round {} 예약 verdict={}", round, next, verdictId);
                return true;
            }
            schedulerQueries.clearActiveGeneration(verdictId);
            // 운영 알림 채널이 없어 WARN 로그로 남긴다
            log.warn("운영 알림: TEXT_RETRY round {} 까지 실패, 템플릿 유지 verdict={} code=LEASE_EXPIRED",
                    GenerationFailedService.MAX_RETRY_ROUND, verdictId);
            return true;
        }));
    }

    private List<UUID> safeScan(java.util.function.Supplier<List<UUID>> scan) {
        try {
            return scan.get();
        } catch (RuntimeException e) {
            log.error("10 §7 재시도 스캔 실패", e);
            return List.of();
        }
    }

    private static int forEach(List<UUID> verdictIds, Predicate<UUID> action, String what) {
        int done = 0;
        for (UUID verdictId : verdictIds) {
            try {
                if (action.test(verdictId)) {
                    done++;
                }
            } catch (RuntimeException e) {
                log.error("10 §7 {} 실패 verdict={}", what, verdictId, e);
            }
        }
        return done;
    }
}
