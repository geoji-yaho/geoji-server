package com.ttegeoji.backend.verdict;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ttegeoji.backend.config.InternalApiException;
import com.ttegeoji.backend.domain.Verdict;
import com.ttegeoji.backend.domain.enums.SentenceStatus;
import com.ttegeoji.backend.privacy.PrivacyEpochRepository;
import com.ttegeoji.backend.repository.VerdictRepository;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 10 §4.6 generation-failed. 현재 generation 만 처리한다. 최초 확정(PENDING)이면 코드 표대로 폴백,
 * TEXT_RETRY 중(FINAL)이면 템플릿을 유지하고 다음 round 를 pending_retry_at 으로 예약한다(INSERT 는 스케줄러, §7).
 * 마지막 실패 generation·코드는 verdicts.last_failed_* 에 남겨 같은 요청 재전송을 200 으로 흡수한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GenerationFailedService {

    public static final String INVALID_REQUEST = "INVALID_REQUEST";

    // 10 §4.6 표 1행: 즉시 폴백, round 예약 안 함
    static final Set<String> IMMEDIATE_FALLBACK_CODES = Set.of("AI_NOT_READY", "POLICY_ERROR", "EVIDENCE_INVALIDATED");
    // 10 §4.6 표 2행: 폴백 + round 1
    static final Set<String> ROUND_RETRY_CODES =
            Set.of("VENDOR_UNAVAILABLE", "BUDGET_EXCEEDED", "EVAL_FAILED", "SCHEMA_INVALID", "DEADLINE_EXCEEDED");
    // 10 §7 retry_round <= 3, round n 은 템플릿 후 5·10·20분
    static final int MAX_RETRY_ROUND = 3;
    static final List<Duration> ROUND_DELAYS = List.of(
            VerdictFallbackService.ROUND_1_DELAY, Duration.ofMinutes(10), Duration.ofMinutes(20));

    public record Request(
            @NotNull @JsonProperty("job_id") UUID jobId,
            @NotNull @JsonProperty("generation_id") UUID generationId,
            @NotNull @JsonProperty("error_code") String errorCode) {
    }

    public record Response(@JsonProperty("verdict_id") UUID verdictId) {
    }

    private final GenerationQueries generationQueries;
    private final PrivacyEpochRepository privacyEpochRepository;
    private final VerdictRepository verdictRepository;
    private final VerdictFallbackService fallbackService;

    @Transactional
    public Response fail(UUID verdictId, Request request, String traceId) {
        String code = request.errorCode();
        if (!IMMEDIATE_FALLBACK_CODES.contains(code) && !ROUND_RETRY_CODES.contains(code)) {
            throw new InternalApiException(HttpStatus.UNPROCESSABLE_CONTENT, INVALID_REQUEST);
        }
        privacyEpochRepository.lockAndRead(generationQueries.caseScope(verdictId)
                .orElseThrow(BeginGenerationService::notFound).scopeKeys());
        Verdict verdict = verdictRepository.findByIdForUpdate(verdictId).orElseThrow(BeginGenerationService::notFound);

        if (request.generationId().equals(verdict.getLastFailedGenerationId()) && code.equals(verdict.getLastFailedCode())) {
            return new Response(verdictId);
        }
        if (!request.generationId().equals(verdict.getActiveGenerationId())) {
            throw BeginGenerationService.stale();
        }

        if (verdict.getSentenceStatus() == SentenceStatus.PENDING) {
            fallbackService.applyFallback(verdict, ROUND_RETRY_CODES.contains(code), traceId);
        } else {
            scheduleNextRound(verdict, code);
        }
        verdict.setLastFailedGenerationId(request.generationId());
        verdict.setLastFailedCode(code);
        return new Response(verdictId);
    }

    // 10 §4.6 표 3행·§7: TEXT_RETRY round 안 실패. 문구·형량은 건드리지 않는다
    private void scheduleNextRound(Verdict verdict, String code) {
        verdict.setActiveJobId(null);
        verdict.setActiveGenerationId(null);
        int round = verdict.getRetryRound();
        if (round < MAX_RETRY_ROUND) {
            int next = round + 1;
            verdict.setRetryRound(next);
            verdict.setPendingRetryAt(generationQueries.dbNow().plus(ROUND_DELAYS.get(next - 1)));
            return;
        }
        verdict.setPendingRetryAt(null);
        // 운영 알림 채널이 없어 WARN 로그로 남긴다
        log.warn("운영 알림: TEXT_RETRY round {} 까지 실패, 템플릿 유지 verdict={} code={}",
                MAX_RETRY_ROUND, verdict.getId(), code);
    }
}
