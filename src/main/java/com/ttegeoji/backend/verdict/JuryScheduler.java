package com.ttegeoji.backend.verdict;

import com.ttegeoji.backend.verdict.JuryQueries.PendingVerdict;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 10 §3·§6 배심 스케줄러. 250ms 주기로 마감 지난 게시물을 확정하고, 게이트가 보류한 verdict 에 SENTENCE 를 다시 시도한다.
 * 여러 인스턴스여도 posts·verdicts 행 잠금과 dedupe 로 한 번만 넣는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JuryScheduler {

    private final JuryQueries juryQueries;
    private final VerdictConfirmationService confirmationService;
    private final SentenceGate sentenceGate;

    @Scheduled(fixedDelay = 250)
    public void runCycle() {
        try {
            confirmationService.confirmDue(juryQueries.dbNow());
        } catch (RuntimeException e) {
            log.error("10 §3 마감 스캔 실패", e);
        }
        for (PendingVerdict pending : juryQueries.pendingVerdicts()) {
            try {
                sentenceGate.tryInsert(pending);
            } catch (RuntimeException e) {
                log.error("10 §3 SENTENCE 게이트 실패 verdict={}", pending.verdictId(), e);
            }
        }
    }
}
