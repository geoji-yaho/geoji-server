package com.ttegeoji.backend.verdict;

import com.ttegeoji.backend.domain.Verdict;
import com.ttegeoji.backend.domain.VerdictText;
import com.ttegeoji.backend.domain.enums.ContentSource;
import com.ttegeoji.backend.domain.enums.MemeTag;
import com.ttegeoji.backend.domain.enums.Sentence;
import com.ttegeoji.backend.domain.enums.SentenceStatus;
import com.ttegeoji.backend.domain.enums.SpiceLevel;
import com.ttegeoji.backend.domain.enums.TextStatus;
import com.ttegeoji.backend.domain.enums.VerdictType;
import com.ttegeoji.backend.jobs.JobEnqueuer;
import com.ttegeoji.backend.repository.VerdictTextRepository;
import com.ttegeoji.backend.util.Json;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 판결 폴백(10 §4.6 코드 표·§6 3·5단계). generation-failed 와 watchdog 이 같이 쓴다.
 * 호출자가 잠금 순서(privacy scope → verdict → job)대로 verdict 까지 잠근 뒤 같은 트랜잭션에서 부른다.
 * 이전 job CANCELLED(§6 4단계)는 watchdog 몫이라 여기서 하지 않는다.
 */
@Service
@RequiredArgsConstructor
public class VerdictFallbackService {

    // 10 §7 round 1 은 템플릿 후 5분
    static final Duration ROUND_1_DELAY = Duration.ofMinutes(5);
    // AI 저장소 graphs/templates.py 와 같다. 템플릿 문장은 근거를 인용하지 않는다
    private static final String TEMPLATE_STATEMENT_KIND = "opinion";

    private final TemplateCatalog templateCatalog;
    private final GenerationQueries generationQueries;
    private final VerdictTextRepository verdictTextRepository;
    private final JobEnqueuer jobEnqueuer;
    private final MemeSelector memeSelector;

    /**
     * 미확정 형량을 fallback_sentence FINAL/RULE 로, target_intensities 전 강도 문구를 TEMPLATE 로 확정한다.
     * 이미 FINAL 이면 반복하지 않는다(§6 2단계) — 형량 FINAL 1회·RETAIN 1개.
     *
     * @param retryRound1 true 면 TEXT_RETRY round 1 을 pending_retry_at 으로 예약한다(INSERT 는 스케줄러)
     * @return 이번 호출이 확정했으면 true
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean applyFallback(Verdict verdict, boolean retryRound1, String traceId) {
        if (verdict.getSentenceStatus() == SentenceStatus.FINAL) {
            return false;
        }
        GenerationQueries.JuryCounts counts = generationQueries.juryCounts(verdict.getPostId(), verdict.getRoomId());
        VerdictType result = verdict.getJuryResult();
        Sentence sentence = result == VerdictType.guilty ? fallbackSentence(verdict) : null;
        TemplateCatalog.Rendered rendered = templateCatalog.render(result.name(), counts.juryCount(),
                counts.guiltyCount(), sentence == null ? null : sentence.name());

        verdict.setSentenceStatus(SentenceStatus.FINAL);
        if (sentence != null) {
            verdict.setSentence(sentence);
            verdict.setSentenceSource(ContentSource.RULE);
            verdict.setSentencingReason(rendered.sentencingReason());
            verdict.setReasonSource(ContentSource.TEMPLATE);
        }

        long textVersion = verdict.getTextVersion() + 1;
        String statement = Json.write(rendered.statement().stream()
                .map(text -> Map.of("text", text, "kind", TEMPLATE_STATEMENT_KIND, "evidence_labels", List.of()))
                .toList());
        for (SpiceLevel intensity : targetIntensities(verdict)) {
            VerdictText text = verdictTextRepository.findByVerdictIdAndIntensity(verdict.getId(), intensity)
                    .orElseGet(() -> VerdictText.builder().verdictId(verdict.getId()).intensity(intensity).build());
            text.setHeadline(rendered.headline());
            text.setStatement(statement);
            text.setSource(ContentSource.TEMPLATE);
            text.setTextVersion(textVersion);
            text.setDossierId(null);
            text.setPrivacyEpochSnapshot(null);
            verdictTextRepository.save(text);
        }
        verdict.setTextVersion(textVersion);
        verdict.setTextStatus(TextStatus.TEMPLATE_READY);
        verdict.setMemeImageId(fallbackMeme(verdict, result, sentence));
        verdict.setActiveJobId(null);
        verdict.setActiveGenerationId(null);

        if (retryRound1) {
            verdict.setRetryRound(1);
            verdict.setPendingRetryAt(generationQueries.dbNow().plus(ROUND_1_DELAY));
        }
        jobEnqueuer.enqueueRetainVerdict(verdict.getId().toString(), verdict.getVerdictVersion(), traceId);
        return true;
    }

    /**
     * 템플릿으로 떨어져도 짤은 붙인다(9/19). finalize 는 짤을 고르는데 이 경로는 안 골라서,
     * AI 생성이 실패한 판결만 화면에서 짤 자리가 비었다.
     *
     * <p>서기가 준 meme_tag·감정·키워드가 없으므로 평결과 형량만으로 태그를 정한다.
     * 점수는 태그 일치와 "최근에 안 쓴 것" 만으로 갈린다. AI 가 고른 것보다 덜 맞을 수 있지만
     * 빈 자리보다 낫다는 판단이다.
     */
    private UUID fallbackMeme(Verdict verdict, VerdictType result, Sentence sentence) {
        if (verdict.getMemeImageId() != null) {
            return verdict.getMemeImageId();
        }
        MemeTag tag = tagOf(result, sentence);
        if (tag == null) {
            return null;
        }
        UUID authorId = generationQueries.findAuthorId(verdict.getPostId()).orElse(null);
        if (authorId == null) {
            return null;
        }
        return memeSelector.selectOnce(null, verdict.getId(), verdict.getPostId(), authorId,
                new MemeSelector.Hints(tag.name(), null, null, List.of()));
    }

    /** 10 §11 짤 태그 5종. 각하는 판결문 자체가 없어 여기 오지 않는다 */
    private static MemeTag tagOf(VerdictType result, Sentence sentence) {
        return switch (result) {
            case guilty -> sentence == Sentence.probation ? MemeTag.GUILTY_LIGHT : MemeTag.GUILTY_HEAVY;
            case notGuilty -> MemeTag.NOT_GUILTY;
            case agree -> MemeTag.APPROVED;
            case disagree -> MemeTag.REJECTED;
            case dismissed -> null;
        };
    }

    private static Sentence fallbackSentence(Verdict verdict) {
        Object code = asMap(Json.read(verdict.getPolicySnapshot())).get("fallback_sentence");
        if (!(code instanceof String s)) {
            throw new IllegalStateException("policy_snapshot.fallback_sentence 가 없습니다: verdict " + verdict.getId());
        }
        return Sentence.valueOf(s);
    }

    private static List<SpiceLevel> targetIntensities(Verdict verdict) {
        if (!(Json.read(verdict.getTargetIntensities()) instanceof List<?> values)) {
            throw new IllegalStateException("target_intensities 가 배열이 아닙니다: verdict " + verdict.getId());
        }
        return values.stream().map(v -> SpiceLevel.valueOf(String.valueOf(v))).distinct().toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object json) {
        if (!(json instanceof Map<?, ?>)) {
            throw new IllegalStateException("policy_snapshot 이 객체가 아닙니다");
        }
        return (Map<String, Object>) json;
    }
}
