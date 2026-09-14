package com.ttegeoji.backend.internal;

import com.ttegeoji.backend.config.InternalApiException;
import com.ttegeoji.backend.internal.InternalQueries.PostRow;
import com.ttegeoji.backend.internal.InternalQueries.RoomRow;
import com.ttegeoji.backend.internal.InternalQueries.VerdictRow;
import com.ttegeoji.backend.internal.dto.Audience;
import com.ttegeoji.backend.internal.dto.CaseSnapshot;
import com.ttegeoji.backend.internal.dto.CommentSnapshot;
import com.ttegeoji.backend.internal.dto.JurySnapshot;
import com.ttegeoji.backend.internal.dto.PrivacyVersion;
import com.ttegeoji.backend.internal.dto.RoomSnapshot;
import com.ttegeoji.backend.internal.dto.SentencingPolicy;
import com.ttegeoji.backend.internal.dto.VerdictFinal;
import com.ttegeoji.backend.jobs.JobKind;
import com.ttegeoji.backend.jobs.JobRow;
import com.ttegeoji.backend.privacy.PrivacyEpochRepository;
import com.ttegeoji.backend.privacy.ScopeKeys;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 10 §4.1 CaseSnapshot 조립. job 검증(409)은 호출자가 끝낸 뒤 부른다. job payload 가 가리키는 사건만 읽는다.
 */
@Component
@RequiredArgsConstructor
public class CaseSnapshotAssembler {

    // jsonb 를 DTO 로 읽을 때 저장 모양이 스키마와 다르면 워커에게 넘기지 않고 실패시킨다
    private static final JsonMapper JSONB = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .build();

    private final InternalQueries queries;
    private final PrivacyEpochRepository privacyEpochs;
    private final CommentSource commentSource;

    /** 스냅샷 재료가 DB 에 없거나 모양이 틀림. 워커 잘못이 아니므로 4xx 가 아니라 500 으로 둔다. */
    public static class SnapshotAssemblyException extends RuntimeException {
        SnapshotAssemblyException(String message) {
            super(message);
        }
    }

    private record CaseSource(PostRow post, VerdictRow juryVerdict, VerdictFinal verdictFinal,
                              CommentSnapshot comment) {
    }

    @Transactional(readOnly = true)
    public CaseSnapshot assemble(JobRow job) {
        Map<String, Object> payload = readJsonb(job.payload(), new TypeReference<>() {
        });
        CaseSource source = switch (job.kind()) {
            case PREPARE -> new CaseSource(requirePost(uuid(payload, "post_id")), null, null, null);
            case SENTENCE -> new CaseSource(requirePost(uuid(payload, "post_id")),
                    requireVerdict(uuid(payload, "verdict_id")), null, null);
            case TEXT_RETRY -> {
                // TEXT_RETRY payload 에는 post_id 가 없다(10 §3)
                VerdictRow verdict = requireVerdict(uuid(payload, "verdict_id"));
                yield new CaseSource(requirePost(verdict.postId()), verdict, null, null);
            }
            case RETAIN -> retainSource(payload);
        };
        rejectIfDeleted(job, source.post());
        return build(source);
    }

    private CaseSource retainSource(Map<String, Object> payload) {
        Object event = payload.get("event");
        if (JobKind.EVENT_SENTENCE_FINALIZED.equals(event)) {
            VerdictRow verdict = requireVerdict(uuid(payload, "verdict_id"));
            return new CaseSource(requirePost(verdict.postId()), verdict, verdictFinal(verdict), null);
        }
        if (JobKind.EVENT_COMMENT_APPROVED.equals(event)) {
            Object commentId = payload.get("comment_id");
            CommentSnapshot comment = commentSource.find(commentId instanceof String s ? s : null)
                    .orElseThrow(CaseSnapshotAssembler::notFound);
            return new CaseSource(requirePost(parseUuid(comment.postId(), "comment.post_id")), null, null, comment);
        }
        throw new SnapshotAssemblyException("RETAIN payload event 를 알 수 없다");
    }

    /**
     * 삭제된 원본이면 404 NOT_FOUND — 워커는 skip 한다(10 §4.1 9/14).
     * 10 은 RETAIN(원본 게시물 deleted_at)만 정한다. 다른 kind 에도 적용할지는 미결이라 범위가 정해지면 여기만 고친다.
     */
    private static void rejectIfDeleted(JobRow job, PostRow post) {
        if (job.kind() == JobKind.RETAIN && post.deletedAt() != null) {
            throw notFound();
        }
    }

    private CaseSnapshot build(CaseSource source) {
        PostRow post = source.post();
        List<RoomRow> rooms = queries.findSharedRooms(post.id());

        List<String> roomIds = rooms.stream().map(room -> room.id().toString()).toList();
        List<RoomSnapshot> roomSnapshots = rooms.stream()
                .map(room -> new RoomSnapshot(room.id().toString(), room.spiceLevel(), room.ruleVersion()))
                .toList();

        List<String> scopeKeys = new ArrayList<>();
        scopeKeys.add(ScopeKeys.post(post.id()));
        scopeKeys.add(ScopeKeys.user(post.authorId()));
        rooms.forEach(room -> scopeKeys.add(ScopeKeys.room(room.id())));
        List<PrivacyVersion> privacyVersions = privacyEpochs.read(scopeKeys).entrySet().stream()
                .map(e -> new PrivacyVersion(e.getKey(), e.getValue()))
                .toList();

        Map<String, Object> intakeResult = post.submissionId() == null ? null
                : queries.findIntakeResult(post.submissionId())
                .map(json -> readJsonb(json, new TypeReference<Map<String, Object>>() {
                }))
                .orElse(null);

        JurySnapshot jury = source.juryVerdict() == null ? null : jury(post, source.juryVerdict());

        return new CaseSnapshot(
                CaseSnapshot.SCHEMA_VERSION,
                post.id().toString(),
                post.authorId().toString(),
                post.version(),
                post.item(),
                post.reason(),
                post.amountKrw(),
                post.category(),
                post.postType(),
                rfc3339(post.createdAt()),
                new Audience(roomIds, post.audienceVersion(), post.publicShareEnabled()),
                privacyVersions,
                roomSnapshots,
                intakeResult,
                jury,
                source.verdictFinal(),
                source.comment());
    }

    private JurySnapshot jury(PostRow post, VerdictRow verdict) {
        Map<String, Integer> voteCounts = voteCounts(post.postType(), queries.countVotesByVerdict(post.id()));
        return new JurySnapshot(
                verdict.id().toString(),
                verdict.verdictVersion(),
                verdict.juryResult(),
                voteCounts,
                guiltyRatio(post.postType(), voteCounts),
                rfc3339(verdict.confirmedAt()),
                rfc3339(verdict.deadlineAt()),
                readJsonb(verdict.policySnapshot(), new TypeReference<SentencingPolicy>() {
                }),
                readJsonb(verdict.targetIntensities(), new TypeReference<List<String>>() {
                }),
                verdict.defaultIntensity());
    }

    private static VerdictFinal verdictFinal(VerdictRow verdict) {
        // applied_intensity 가 NULL 일 때 무엇을 줄지는 미결이라 컬럼 값을 그대로 싣는다
        return new VerdictFinal(
                verdict.sentence(),
                verdict.sentenceSource(),
                verdict.sentencingReason(),
                verdict.reasonSource(),
                verdict.appliedIntensity(),
                banterStrategy(verdict));
    }

    // 미결: 코디네이터 답 대기. 10 §2 에 banter_strategy 저장 자리가 없고 강도별 값 중 하나를 고르는 규칙도 없다.
    // 읽을 곳이 없어 null(스키마 허용)이다. 값이 정해지면 여기만 고친다
    private static String banterStrategy(VerdictRow verdict) {
        return null;
    }

    /** spent 는 guilty·notGuilty, considering 은 agree·disagree 를 0 포함으로 채운다. 다른 값의 표는 세지 않는다. */
    static Map<String, Integer> voteCounts(String postType, Map<String, Integer> counted) {
        String[] keys = voteKeys(postType);
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String key : keys) {
            counts.put(key, counted.getOrDefault(key, 0));
        }
        return counts;
    }

    /**
     * 0..1 소수(10 §4.1 9/11). spent = guilty ÷ (guilty+notGuilty), considering = disagree ÷ (agree+disagree)
     * (9/15 결정, AI 픽스처 jury-rejected.json 과 같다). 표 0 이면 0.
     */
    static double guiltyRatio(String postType, Map<String, Integer> voteCounts) {
        String[] keys = voteKeys(postType);
        int yes = voteCounts.getOrDefault(keys[0], 0);
        int no = voteCounts.getOrDefault(keys[1], 0);
        int total = yes + no;
        if (total == 0) {
            return 0.0;
        }
        int numerator = "spent".equals(postType) ? yes : no;
        return (double) numerator / total;
    }

    private static String[] voteKeys(String postType) {
        return switch (postType == null ? "" : postType) {
            case "spent" -> new String[]{"guilty", "notGuilty"};
            case "considering" -> new String[]{"agree", "disagree"};
            default -> throw new SnapshotAssemblyException("모르는 게시물 유형");
        };
    }

    private PostRow requirePost(UUID postId) {
        return queries.findPost(postId).orElseThrow(() -> new SnapshotAssemblyException("job 이 가리키는 post 가 없다"));
    }

    private VerdictRow requireVerdict(UUID verdictId) {
        return queries.findVerdict(verdictId)
                .orElseThrow(() -> new SnapshotAssemblyException("job 이 가리키는 verdict 가 없다"));
    }

    private static UUID uuid(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return parseUuid(value instanceof String s ? s : null, key);
    }

    private static UUID parseUuid(String value, String name) {
        if (value == null) {
            throw new SnapshotAssemblyException(name + " 가 없다");
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new SnapshotAssemblyException(name + " 가 UUID 가 아니다");
        }
    }

    private static <T> T readJsonb(String json, TypeReference<T> type) {
        if (json == null) {
            throw new SnapshotAssemblyException("jsonb 값이 없다");
        }
        try {
            return JSONB.readValue(json, type);
        } catch (JacksonException e) {
            // 원문을 메시지에 싣지 않는다(사유가 섞일 수 있다)
            throw new SnapshotAssemblyException("jsonb 모양이 스냅샷 스키마와 다르다");
        }
    }

    private static String rfc3339(OffsetDateTime time) {
        return time == null ? null : time.withOffsetSameInstant(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private static InternalApiException notFound() {
        return new InternalApiException(HttpStatus.NOT_FOUND, "NOT_FOUND");
    }
}
