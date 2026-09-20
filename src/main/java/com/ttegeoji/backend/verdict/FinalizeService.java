package com.ttegeoji.backend.verdict;

import com.ttegeoji.backend.config.GeojiProperties;
import com.ttegeoji.backend.config.InternalApiException;
import com.ttegeoji.backend.domain.Verdict;
import com.ttegeoji.backend.domain.VerdictText;
import com.ttegeoji.backend.domain.enums.ContentSource;
import com.ttegeoji.backend.domain.enums.Sentence;
import com.ttegeoji.backend.domain.enums.SentenceStatus;
import com.ttegeoji.backend.domain.enums.SpiceLevel;
import com.ttegeoji.backend.domain.enums.TextStatus;
import com.ttegeoji.backend.domain.enums.VerdictType;
import com.ttegeoji.backend.jobs.JobEnqueuer;
import com.ttegeoji.backend.jobs.JobKind;
import com.ttegeoji.backend.privacy.PrivacyEpochRepository;
import com.ttegeoji.backend.privacy.ScopeKeys;
import com.ttegeoji.backend.repository.VerdictRepository;
import com.ttegeoji.backend.repository.VerdictTextRepository;
import com.ttegeoji.backend.util.Json;
import com.ttegeoji.backend.verdict.CommitRecordRepository.CommitRecord;
import com.ttegeoji.backend.verdict.FinalizeQueries.Dossier;
import com.ttegeoji.backend.verdict.FinalizeQueries.EvidenceState;
import com.ttegeoji.backend.verdict.FinalizeQueries.LockedJob;
import com.ttegeoji.backend.verdict.FinalizeQueries.VerdictHeader;
import com.ttegeoji.backend.verdict.FinalizeRequestParser.FinalizeRequest;
import com.ttegeoji.backend.verdict.FinalizeRequestParser.PrivacyVersion;
import com.ttegeoji.backend.verdict.FinalizeRequestParser.Statement;
import com.ttegeoji.backend.verdict.FinalizeRequestParser.TextDraft;
import com.ttegeoji.backend.verdict.FinalizeRequestParser.TextEvaluation;
import com.ttegeoji.backend.verdict.TextEvidenceRefRepository.TextEvidenceRef;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * 10 §5 finalize 12단계를 한 트랜잭션으로. 검사 순서는 10 §5 "가짜 백엔드 검사 순서"와 같다.
 * commit record → 스키마 → verdict 404 → 잠금(privacy scope → verdict → job) → commit record 재확인 →
 * 원본 삭제·epoch·공유 방(409 EVIDENCE_INVALIDATED) → job·generation·버전(409 STALE_GENERATION) →
 * 마감(409 DEADLINE_EXCEEDED) → 결정적 검증(422 INVALID_DRAFT) → 형량 → 문구 → text_status·짤·active 해제 →
 * RETAIN(최초만) → job SUCCEEDED·commit record.
 * 호출자가 보낸 평결·default intensity·허용 목록·sentence_source 는 쓰지 않고 verdicts 행에서 읽는다.
 * 트랜잭션 안에서 HTTP 를 부르지 않는다.
 */
@Service
@RequiredArgsConstructor
public class FinalizeService {

    static final String STALE_GENERATION = "STALE_GENERATION";
    static final String IDEMPOTENCY_CONFLICT = "IDEMPOTENCY_CONFLICT";
    static final String EVIDENCE_INVALIDATED = "EVIDENCE_INVALIDATED";
    static final String DEADLINE_EXCEEDED = "DEADLINE_EXCEEDED";
    static final String NOT_FOUND = "NOT_FOUND";

    /** 10 §7 round 1 은 템플릿 후 5분 */
    static final int FIRST_RETRY_DELAY_MINUTES = 5;
    /** 10 §5·9/11 statement 합산 상한 */
    static final int STATEMENT_TOTAL_MAX = 300;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record FinalizeResult(UUID verdictId, long textVersion, OffsetDateTime committedAt) {
    }

    private final CommitRecordRepository commitRecords;
    private final PrivacyEpochRepository privacyEpochs;
    private final VerdictRepository verdictRepository;
    private final VerdictTextRepository verdictTextRepository;
    private final TextEvidenceRefRepository textEvidenceRefs;
    private final FinalizeQueries queries;
    private final MemeSelector memeSelector;
    private final JobEnqueuer jobEnqueuer;
    private final GeojiProperties properties;

    @Transactional
    public FinalizeResult finalizeVerdict(String verdictIdPath, byte[] body) {
        String requestHash = sha256Hex(body);
        UUID generationId = FinalizeRequestParser.peekGenerationId(body);

        // 1. commit record
        var earlier = commitRecords.find(generationId);
        if (earlier.isPresent()) {
            return replay(earlier.get(), requestHash, verdictIdPath);
        }

        FinalizeRequest request = FinalizeRequestParser.parse(body);
        UUID verdictId = uuidOrNotFound(verdictIdPath);
        VerdictHeader header = queries.findVerdictHeader(verdictId).orElseThrow(FinalizeService::notFound);
        UUID postId = header.postId();

        // 2. privacy scope(key 오름차순) → verdict → job. 요청이 가진 key 도 같이 잠가 비교한다
        List<UUID> roomIds = queries.findRoomIds(postId);
        // 방별 판결이면 스냅샷이 그 방 하나만 담아 보냈다(10 §4.1, 9/16). 요구하는 scope 도 그 방까지다.
        // 게시물 전체 방을 요구하면 두 방 이상에 올린 글은 항상 EVIDENCE_INVALIDATED 로 막힌다
        List<UUID> scopeRooms = header.roomId() == null ? roomIds : List.of(header.roomId());
        Set<String> requiredKeys = scopeKeys(postId, header.authorId(), scopeRooms);
        Set<String> lockKeys = new TreeSet<>(requiredKeys);
        request.privacyVersions().forEach(pv -> lockKeys.add(pv.scopeKey()));
        Map<String, Long> epochs = privacyEpochs.lockAndRead(lockKeys);
        Verdict verdict = verdictRepository.findByIdForUpdate(verdictId).orElseThrow(FinalizeService::notFound);
        UUID jobId = uuidOrNull(request.jobId());
        LockedJob job = jobId == null ? null : queries.lockJob(jobId).orElse(null);

        // 3. commit record 재확인(동시 동일 요청 흡수)
        earlier = commitRecords.find(generationId);
        if (earlier.isPresent()) {
            return replay(earlier.get(), requestHash, verdictIdPath);
        }

        // 3. 원본 삭제·epoch·공유 방(audience) 재확인
        if (queries.findPostState(postId).deleted()
                || !queries.findRoomIds(postId).equals(roomIds)
                || !privacyVersionsCurrent(request.privacyVersions(), requiredKeys, epochs)) {
            throw conflict(EVIDENCE_INVALIDATED);
        }
        UUID dossierId = uuidOrNull(request.dossierId());
        Dossier dossier = dossierId == null ? null : queries.findDossier(dossierId).orElse(null);
        if (dossier != null && dossier.invalidated()) {
            throw conflict(EVIDENCE_INVALIDATED);
        }

        // 4·5. job RUNNING ∧ lease ∧ generation, verdict_version·active_*·expected_text_version
        boolean firstFix = verdict.getSentenceStatus() == SentenceStatus.PENDING;
        String expectedKind = firstFix ? JobKind.SENTENCE.name() : JobKind.TEXT_RETRY.name();
        if (job == null
                || !"RUNNING".equals(job.status())
                || !job.leaseValid()
                || !generationId.equals(job.generationId())
                || !expectedKind.equals(job.kind())
                || request.verdictVersion() != verdict.getVerdictVersion()
                || !job.id().equals(verdict.getActiveJobId())
                || !generationId.equals(verdict.getActiveGenerationId())
                || request.expectedTextVersion() != verdict.getTextVersion()) {
            throw conflict(STALE_GENERATION);
        }

        // 6. 최초 SENTENCE 는 verdicts.deadline_at, TEXT_RETRY 는 job 자체 제한 시각
        if (firstFix ? queries.verdictDeadlinePassed(verdictId) : job.deadlinePassed()) {
            throw conflict(DEADLINE_EXCEEDED);
        }

        // 7. 결정적 검증
        List<SpiceLevel> targets = targetIntensities(verdict);
        Map<String, UUID> evidenceByLabel = validate(request, verdict, firstFix, targets, postId, dossier);

        // 8. 형량. 비유죄(notGuilty·agree·disagree)는 형량이 없어 sentencing 이 null 로 온다(10 §16.6-3)
        if (firstFix) {
            var sentencing = request.sentencing();
            verdict.setSentenceStatus(SentenceStatus.FINAL);
            if (sentencing == null) {
                verdict.setSentence(null);
                verdict.setSentenceSource(null);
                verdict.setSentencingReason(null);
                verdict.setReasonSource(null);
            } else {
                verdict.setSentence(Sentence.valueOf(sentencing.sentence()));
                verdict.setSentenceSource(ContentSource.AI);
                verdict.setSentencingReason(sentencing.sentencingReason());
                verdict.setReasonSource(ContentSource.valueOf(sentencing.reasonSource()));
            }
        }

        // 9. 받은 강도 행을 새 text_version 으로. 나머지 강도 행은 그대로(TEXT_RETRY ⊆, 9/14 채택)
        long textVersion = verdict.getTextVersion() + 1;
        String epochSnapshot = epochSnapshot(epochs);
        Map<SpiceLevel, VerdictText> rows = new EnumMap<>(SpiceLevel.class);
        verdictTextRepository.findByVerdictId(verdictId).forEach(row -> rows.put(row.getIntensity(), row));
        List<TextEvidenceRef> refs = new ArrayList<>();
        for (TextDraft text : request.draft().texts()) {
            SpiceLevel intensity = SpiceLevel.valueOf(text.intensity());
            VerdictText row = rows.computeIfAbsent(intensity,
                    i -> VerdictText.builder().verdictId(verdictId).intensity(i).build());
            row.setHeadline(text.headline());
            row.setStatement(text.statementJson());
            row.setSource(ContentSource.valueOf(text.source()));
            row.setTextVersion(textVersion);
            row.setDossierId(dossierId);
            row.setPrivacyEpochSnapshot(epochSnapshot);
            refs.addAll(evidenceRefs(verdictId, textVersion, intensity, text, evidenceByLabel));
        }
        verdictTextRepository.saveAll(rows.values());

        // 10. text_status·짤 고정·active 해제
        boolean allAi = targets.stream()
                .allMatch(i -> rows.containsKey(i) && rows.get(i).getSource() == ContentSource.AI);
        if (allAi) {
            verdict.setTextStatus(TextStatus.AI_READY);
            verdict.setPendingRetryAt(null);
        } else {
            verdict.setTextStatus(TextStatus.TEMPLATE_READY);
            if (firstFix) {
                verdict.setRetryRound(1);
                verdict.setPendingRetryAt(queries.dbNowPlusMinutes(FIRST_RETRY_DELAY_MINUTES));
            }
        }
        verdict.setMemeImageId(memeSelector.selectOnce(verdict.getMemeImageId(), verdictId, postId,
                header.authorId(), memeHints(request, verdict)));
        verdict.setTextVersion(textVersion);
        verdict.setActiveJobId(null);
        verdict.setActiveGenerationId(null);
        verdictRepository.flush();
        textEvidenceRefs.insertAll(refs);

        // 11. 최초 형량 확정 때만 RETAIN
        if (firstFix) {
            jobEnqueuer.enqueueRetainVerdict(verdictId.toString(), verdict.getVerdictVersion());
        }

        // 12. job SUCCEEDED, commit record. 응답은 commit 사실만(문구 없음)
        queries.markJobSucceeded(job.id());
        CommitRecord record = commitRecords.insert(generationId, requestHash, verdictId, textVersion);
        return new FinalizeResult(record.verdictId(), record.textVersion(), record.committedAt());
    }

    private Map<String, UUID> validate(FinalizeRequest request, Verdict verdict, boolean firstFix,
                                       List<SpiceLevel> targets, UUID postId, Dossier dossier) {
        // hash 형식 → 정책 버전 = 설정값(10 §5 가짜 백엔드 검사 순서, GUARDRAIL_POLICY_VERSION)
        if (!FinalizeRequestParser.SHA256_HEX.matcher(request.draftHash()).matches()
                || !FinalizeRequestParser.SHA256_HEX.matcher(request.evaluationDraftHash()).matches()) {
            throw FinalizeRequestParser.invalid();
        }
        String configured = properties.guardrailPolicyVersion();
        if (configured == null || configured.isBlank()
                || !configured.equals(request.guardrailPolicyVersion())
                || !configured.equals(request.evaluation().policyVersion())) {
            throw FinalizeRequestParser.invalid();
        }

        // draft_hash 재계산. 짝 없는 서로게이트·NaN 은 DraftHash 가 IllegalArgumentException — 500 이 아니라 422
        String recomputed;
        try {
            recomputed = DraftHash.sha256Hex(request.draftNode(), request.sentencingNode());
        } catch (IllegalArgumentException e) {
            throw FinalizeRequestParser.invalid();
        }
        if (!recomputed.equals(request.draftHash()) || !recomputed.equals(request.evaluationDraftHash())) {
            throw FinalizeRequestParser.invalid();
        }

        // 검수 보고서: 누락·false 거부
        var evaluation = request.evaluation();
        if (!evaluation.sentenceCheckPass() || !evaluation.sentencingReasonCheckPass()) {
            throw FinalizeRequestParser.invalid();
        }
        Map<String, Boolean> textPass = new HashMap<>();
        for (TextEvaluation text : evaluation.texts()) {
            textPass.merge(text.intensity(), text.pass(), Boolean::logicalAnd);
        }

        // 강도 집합: 중복 없음, 최초는 = target_intensities, TEXT_RETRY 는 ⊆
        Set<SpiceLevel> given = new LinkedHashSet<>();
        for (TextDraft text : request.draft().texts()) {
            if (!given.add(SpiceLevel.valueOf(text.intensity())) || !Boolean.TRUE.equals(textPass.get(text.intensity()))) {
                throw FinalizeRequestParser.invalid();
            }
        }
        if (firstFix ? !given.equals(Set.copyOf(targets)) : !targets.containsAll(given)) {
            throw FinalizeRequestParser.invalid();
        }

        // 형량: 최초는 DB 허용 목록 안, FINAL 이면 기존 형량·이유와 같아야 한다(D-19)
        var sentencing = request.sentencing();
        if (firstFix && verdict.getJuryResult() != VerdictType.guilty) {
            // 비유죄는 형량이 없다. sentencing 을 보내오면 계약 위반이라 거부한다(10 §16.6-3)
            if (sentencing != null) {
                throw FinalizeRequestParser.invalid();
            }
        } else if (firstFix) {
            JsonNode policy = readJson(verdict.getPolicySnapshot());
            Set<String> allowed = new LinkedHashSet<>();
            policy.path("allowed_sentences").values().forEach(item -> allowed.add(item.path("code").asString("")));
            if (sentencing == null || !allowed.contains(sentencing.sentence()) || !isSentence(sentencing.sentence())) {
                throw FinalizeRequestParser.invalid();
            }
            if (policy.path("reason_required").asBoolean(false)
                    && sentencing.sentencingReason() == null && "AI".equals(sentencing.reasonSource())) {
                throw FinalizeRequestParser.invalid();
            }
        } else if (sentencing != null
                && (verdict.getSentence() == null
                || !verdict.getSentence().name().equals(sentencing.sentence())
                || !Objects.equals(verdict.getSentencingReason(), sentencing.sentencingReason()))) {
            throw FinalizeRequestParser.invalid();
        }

        // 결과: 짤 태그가 배심 결과와 맞아야 한다
        if (!memeTagMatches(verdict, request.draft().memeTag())) {
            throw FinalizeRequestParser.invalid();
        }

        // 길이: statement 합산 300(headline 30·sentencing_reason 100 은 파서가 봤다)
        for (TextDraft text : request.draft().texts()) {
            int total = text.statement().stream().mapToInt(s -> s.text().codePointCount(0, s.text().length())).sum();
            if (total > STATEMENT_TOTAL_MAX) {
                throw FinalizeRequestParser.invalid();
            }
        }

        return resolveLabels(request, postId, dossier);
    }

    /** 근거 라벨 → evidence UUID(ai.dossiers.label_map). 모르는 라벨 422, 무효화된 evidence 409 */
    private Map<String, UUID> resolveLabels(FinalizeRequest request, UUID postId, Dossier dossier) {
        Set<String> labels = new TreeSet<>();
        if (request.sentencing() != null) {
            labels.addAll(request.sentencing().evidenceLabels());
        }
        request.draft().texts().forEach(t -> t.statement().forEach(s -> labels.addAll(s.evidenceLabels())));
        if (dossier == null) {
            if (FinalizeRequestParser.parseUuidOrNull(request.dossierId()) == null || !labels.isEmpty()) {
                throw FinalizeRequestParser.invalid();
            }
            return Map.of();
        }
        if (!postId.toString().equals(dossier.postId())) {
            throw FinalizeRequestParser.invalid();
        }
        JsonNode labelMap = readJson(dossier.labelMapJson());
        Map<String, UUID> byLabel = new LinkedHashMap<>();
        for (String label : labels) {
            JsonNode value = labelMap.get(label);
            UUID evidenceId = value == null || !value.isString()
                    ? null : FinalizeRequestParser.parseUuidOrNull(value.stringValue());
            if (evidenceId == null) {
                throw FinalizeRequestParser.invalid();
            }
            byLabel.put(label, evidenceId);
        }
        Map<UUID, EvidenceState> evidence = queries.findEvidence(dossier.id(), Set.copyOf(byLabel.values()));
        for (UUID evidenceId : byLabel.values()) {
            EvidenceState state = evidence.get(evidenceId);
            if (state == null) {
                throw FinalizeRequestParser.invalid();
            }
            if (state.invalidated()) {
                throw conflict(EVIDENCE_INVALIDATED);
            }
        }
        return byLabel;
    }

    /** field_path 는 AI 저장소 domain/validation.py EvidenceRef 와 같은 statement[j] */
    private static List<TextEvidenceRef> evidenceRefs(UUID verdictId, long textVersion, SpiceLevel intensity,
                                                      TextDraft text, Map<String, UUID> evidenceByLabel) {
        Set<TextEvidenceRef> refs = new LinkedHashSet<>();
        for (int j = 0; j < text.statement().size(); j++) {
            Statement statement = text.statement().get(j);
            for (String label : statement.evidenceLabels()) {
                refs.add(new TextEvidenceRef(verdictId, textVersion, intensity, "statement[" + j + "]",
                        evidenceByLabel.get(label)));
            }
        }
        return List.copyOf(refs);
    }

    /**
     * 요청 privacy_versions 가 지금 epoch 와 같은가. post·작성자·현재 공유 방 key 는 모두 있어야 하고,
     * 요청의 방 key 가 지금 공유 방에 없으면(공유 철회) 불일치다.
     */
    private static boolean privacyVersionsCurrent(List<PrivacyVersion> versions, Set<String> requiredKeys,
                                                  Map<String, Long> epochs) {
        Map<String, Long> given = new HashMap<>();
        for (PrivacyVersion pv : versions) {
            if (given.containsKey(pv.scopeKey()) && given.get(pv.scopeKey()) != pv.epoch()) {
                return false;
            }
            given.put(pv.scopeKey(), pv.epoch());
        }
        if (!given.keySet().containsAll(requiredKeys)) {
            return false;
        }
        for (Map.Entry<String, Long> entry : given.entrySet()) {
            if (!Objects.equals(epochs.get(entry.getKey()), entry.getValue())) {
                return false;
            }
            if (entry.getKey().startsWith("room:") && !requiredKeys.contains(entry.getKey())) {
                return false;
            }
        }
        return true;
    }

    private static Set<String> scopeKeys(UUID postId, UUID authorId, List<UUID> roomIds) {
        Set<String> keys = new TreeSet<>();
        keys.add(ScopeKeys.post(postId));
        keys.add(ScopeKeys.user(authorId));
        roomIds.forEach(roomId -> keys.add(ScopeKeys.room(roomId)));
        return keys;
    }

    private static String epochSnapshot(Map<String, Long> epochs) {
        List<Map<String, Object>> snapshot = new ArrayList<>();
        epochs.forEach((key, epoch) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("scope_key", key);
            item.put("epoch", epoch);
            snapshot.add(item);
        });
        return Json.write(snapshot);
    }

    /** 짤 점수의 banter_strategy 는 default_intensity 문구, 이번 요청에 없으면 첫 문구 */
    private static MemeSelector.Hints memeHints(FinalizeRequest request, Verdict verdict) {
        List<TextDraft> texts = request.draft().texts();
        String strategy = texts.stream()
                .filter(t -> verdict.getDefaultIntensity() != null && verdict.getDefaultIntensity().name().equals(t.intensity()))
                .findFirst()
                .orElse(texts.getFirst())
                .banterStrategy();
        return new MemeSelector.Hints(request.draft().memeTag(), strategy, request.draft().memeEmotion(),
                request.draft().memeKeywords());
    }

    /** AI 저장소 domain/validation.py _MEME_BY_RESULT 와 같다. 유죄는 GUILTY_HEAVY·GUILTY_LIGHT 둘 다 허용 */
    private static boolean memeTagMatches(Verdict verdict, String memeTag) {
        if (verdict.getJuryResult() == null) {
            return false;
        }
        return switch (verdict.getJuryResult()) {
            case guilty -> "GUILTY_HEAVY".equals(memeTag) || "GUILTY_LIGHT".equals(memeTag);
            case notGuilty -> "NOT_GUILTY".equals(memeTag);
            case agree -> "APPROVED".equals(memeTag);
            case disagree -> "REJECTED".equals(memeTag);
            case dismissed -> false;
        };
    }

    private static List<SpiceLevel> targetIntensities(Verdict verdict) {
        List<SpiceLevel> targets = new ArrayList<>();
        for (JsonNode item : readJson(verdict.getTargetIntensities()).values()) {
            targets.add(SpiceLevel.valueOf(item.stringValue()));
        }
        return targets;
    }

    private FinalizeResult replay(CommitRecord record, String requestHash, String verdictIdPath) {
        if (!record.requestHash().equals(requestHash) || !record.verdictId().toString().equalsIgnoreCase(verdictIdPath)) {
            throw conflict(IDEMPOTENCY_CONFLICT);
        }
        return new FinalizeResult(record.verdictId(), record.textVersion(), record.committedAt());
    }

    private static boolean isSentence(String code) {
        for (Sentence sentence : Sentence.values()) {
            if (sentence.name().equals(code)) {
                return true;
            }
        }
        return false;
    }

    private static JsonNode readJson(String json) {
        return MAPPER.readTree(json);
    }

    private static UUID uuidOrNotFound(String value) {
        UUID uuid = FinalizeRequestParser.parseUuidOrNull(value);
        if (uuid == null) {
            throw notFound();
        }
        return uuid;
    }

    private static UUID uuidOrNull(String value) {
        return FinalizeRequestParser.parseUuidOrNull(value);
    }

    private static String sha256Hex(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(body == null ? "".getBytes(StandardCharsets.UTF_8) : body));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static InternalApiException conflict(String code) {
        return new InternalApiException(HttpStatus.CONFLICT, code);
    }

    private static InternalApiException notFound() {
        return new InternalApiException(HttpStatus.NOT_FOUND, NOT_FOUND);
    }
}
