package com.ttegeoji.backend.verdict;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ttegeoji.backend.config.InternalApiException;
import com.ttegeoji.backend.domain.Verdict;
import com.ttegeoji.backend.domain.enums.ContentSource;
import com.ttegeoji.backend.domain.enums.Sentence;
import com.ttegeoji.backend.domain.enums.SentenceStatus;
import com.ttegeoji.backend.domain.enums.TextStatus;
import com.ttegeoji.backend.jobs.JobKind;
import com.ttegeoji.backend.privacy.PrivacyEpochRepository;
import com.ttegeoji.backend.repository.VerdictRepository;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 10 §4.3 begin-generation. 잠금 순서 privacy scope → verdict → job(§2) 뒤 lease·generation·평결 버전을 확인하고
 * active_job_id·active_generation_id 를 건다. SENTENCE 는 마감 전 PENDING, TEXT_RETRY 는 템플릿이 노출된 FINAL 에서만 시작한다.
 */
@Service
@RequiredArgsConstructor
public class BeginGenerationService {

    public static final String STALE_GENERATION = "STALE_GENERATION";
    public static final String DEADLINE_EXCEEDED = "DEADLINE_EXCEEDED";
    public static final String NOT_FOUND = "NOT_FOUND";

    public record Request(
            @NotNull @JsonProperty("job_id") UUID jobId,
            @NotNull @JsonProperty("generation_id") UUID generationId,
            @NotNull @JsonProperty("verdict_version") Integer verdictVersion) {
    }

    public record FixedSentencing(
            @JsonProperty("sentence") Sentence sentence,
            @JsonProperty("sentencing_reason") String sentencingReason,
            @JsonProperty("reason_source") ContentSource reasonSource) {
    }

    public record Response(
            @JsonProperty("fixed_sentencing") FixedSentencing fixedSentencing,
            @JsonProperty("text_version") long textVersion,
            @JsonProperty("deadline_at") OffsetDateTime deadlineAt) {
    }

    private final GenerationQueries generationQueries;
    private final PrivacyEpochRepository privacyEpochRepository;
    private final VerdictRepository verdictRepository;

    @Transactional
    public Response begin(UUID verdictId, Request request) {
        GenerationQueries.CaseScope scope = generationQueries.caseScope(verdictId)
                .orElseThrow(BeginGenerationService::notFound);
        privacyEpochRepository.lockAndRead(scope.scopeKeys());
        Verdict verdict = verdictRepository.findByIdForUpdate(verdictId).orElseThrow(BeginGenerationService::notFound);

        List<UUID> jobIds = new ArrayList<>(List.of(request.jobId()));
        if (verdict.getActiveJobId() != null) {
            jobIds.add(verdict.getActiveJobId());
        }
        GenerationQueries.LockedJobs locked = generationQueries.lockJobsWithNow(jobIds);
        List<GenerationQueries.LockedJob> jobs = locked.jobs();
        GenerationQueries.LockedJob requester = find(jobs, request.jobId());

        if (request.verdictVersion() != verdict.getVerdictVersion().intValue()
                || request.generationId().equals(verdict.getLastFailedGenerationId())
                || requester == null
                // SENTENCE·TEXT_RETRY 의 aggregate_id 는 verdict id 다(10 §3). 다른 판결의 job 으로 시작하지 않는다
                || !verdictId.toString().equals(requester.aggregateId())
                || !requester.ownedBy(request.generationId())) {
            throw stale();
        }
        boolean sameCurrent = request.jobId().equals(verdict.getActiveJobId())
                && request.generationId().equals(verdict.getActiveGenerationId());
        if (!sameCurrent && verdict.getActiveJobId() != null) {
            // 다른 활성 generation 이 아직 유효하면 거부. lease 만료·세대 교체면 대체한다
            GenerationQueries.LockedJob active = find(jobs, verdict.getActiveJobId());
            if (active != null && active.ownedBy(verdict.getActiveGenerationId())) {
                throw stale();
            }
        }

        FixedSentencing fixed;
        OffsetDateTime deadlineAt;
        if (verdict.getSentenceStatus() == SentenceStatus.PENDING) {
            if (requester.kind() != JobKind.SENTENCE) {
                throw stale();
            }
            // requester 가 있으니 job 행을 잠갔고 dbNow 도 왔다(lockJobsWithNow)
            if (verdict.getDeadlineAt() != null && !locked.dbNow().isBefore(verdict.getDeadlineAt())) {
                throw new InternalApiException(HttpStatus.CONFLICT, DEADLINE_EXCEEDED);
            }
            fixed = null;
            deadlineAt = verdict.getDeadlineAt() != null ? verdict.getDeadlineAt() : requester.deadlineAt();
            verdict.setTextStatus(TextStatus.GENERATING);
        } else {
            // 템플릿 이후 늦게 온 SENTENCE·AI 문구가 이미 있는 TEXT_RETRY 는 거부(§13 작업 8)
            if (requester.kind() != JobKind.TEXT_RETRY || verdict.getTextStatus() == TextStatus.AI_READY) {
                throw stale();
            }
            fixed = verdict.getSentence() == null
                    ? null
                    : new FixedSentencing(verdict.getSentence(), verdict.getSentencingReason(), verdict.getReasonSource());
            // 판결 마감은 이미 지났다. 워커 시간 예산은 TEXT_RETRY job 마감(INSERT + 20s)
            deadlineAt = requester.deadlineAt() != null ? requester.deadlineAt() : verdict.getDeadlineAt();
        }

        verdict.setActiveJobId(request.jobId());
        verdict.setActiveGenerationId(request.generationId());
        return new Response(fixed, verdict.getTextVersion(), deadlineAt);
    }

    private static GenerationQueries.LockedJob find(List<GenerationQueries.LockedJob> jobs, UUID id) {
        return jobs.stream().filter(j -> j.id().equals(id)).findFirst().orElse(null);
    }

    static InternalApiException stale() {
        return new InternalApiException(HttpStatus.CONFLICT, STALE_GENERATION);
    }

    static InternalApiException notFound() {
        return new InternalApiException(HttpStatus.NOT_FOUND, NOT_FOUND);
    }
}
