package com.ttegeoji.backend.internal;

import com.ttegeoji.backend.internal.InternalQueries.PostRow;
import com.ttegeoji.backend.internal.InternalQueries.RecentVerdictRow;
import com.ttegeoji.backend.internal.InternalQueries.RoomRow;
import com.ttegeoji.backend.internal.InternalQueries.VerdictRow;
import com.ttegeoji.backend.internal.dto.AggregateWindow;
import com.ttegeoji.backend.internal.dto.Aggregates;
import com.ttegeoji.backend.internal.dto.EvidenceCandidate;
import com.ttegeoji.backend.internal.dto.EvidenceInclude;
import com.ttegeoji.backend.internal.dto.EvidenceScope;
import com.ttegeoji.backend.internal.dto.EvidenceScope.Visibility;
import com.ttegeoji.backend.internal.dto.EvidenceSource;
import com.ttegeoji.backend.internal.dto.RecentVerdict;
import com.ttegeoji.backend.internal.dto.ResolveEvidenceRequest;
import com.ttegeoji.backend.internal.dto.ResolveEvidenceResponse;
import com.ttegeoji.backend.internal.dto.RoomRule;
import com.ttegeoji.backend.jobs.JobRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 10 §4.2 resolve-evidence 조립. 자유 조회 API 가 아니다 — job 이 가리키는 사건의 작성자·공유 방·공개 정책 범위만 준다.
 * 요청 본문 검증은 ResolveEvidenceRequest.parse, job 검증은 컨트롤러가 먼저 끝낸다. 규칙 값은 9/15 사용자 답(1~7).
 * 사유 원문이 응답에 실리므로 아무것도 로그하지 않는다.
 */
@Component
@RequiredArgsConstructor
public class EvidenceResolver {

    static final Duration WINDOW = Duration.ofDays(30);
    static final int RECENT_VERDICTS_MAX = 10;
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String POST = "POST";
    private static final String VERDICT = "VERDICT";

    private final CaseSnapshotAssembler caseSnapshotAssembler;
    private final InternalQueries queries;

    @Transactional(readOnly = true)
    public ResolveEvidenceResponse resolve(JobRow job, ResolveEvidenceRequest request) {
        PostRow casePost = caseSnapshotAssembler.casePost(job);
        List<RoomRow> caseRooms = queries.findSharedRooms(casePost.id());
        Set<String> targetRoomIds = new HashSet<>(roomIds(caseRooms));
        Set<EvidenceInclude> include = Set.copyOf(request.include());

        // 답 7: aggregates 는 늘, 나머지는 include 에 없으면 []. 다섯 키는 늘 있다
        return new ResolveEvidenceResponse(
                sources(casePost, targetRoomIds, request.candidates()),
                aggregates(casePost, caseRooms),
                include.contains(EvidenceInclude.rules) ? roomRules(caseRooms) : List.of(),
                include.contains(EvidenceInclude.recent_verdicts) ? recentVerdicts(casePost, targetRoomIds) : List.of(),
                // P0 는 늘 빈 배열(10 §4.2, §15.3 D-04)
                List.of());
    }

    // --- sources(답 5) ---

    private List<EvidenceSource> sources(PostRow casePost, Set<String> targetRoomIds,
                                         List<EvidenceCandidate> candidates) {
        List<EvidenceCandidate> ordered = new ArrayList<>(candidates);
        ordered.sort(Comparator.comparingDouble(EvidenceCandidate::score).reversed());
        Set<String> seen = new HashSet<>();
        List<EvidenceSource> sources = new ArrayList<>();
        for (EvidenceCandidate candidate : ordered) {
            // 같은 출처·버전은 한 번만. 버전이 다르면 stale 판정이 따로 걸러낸다
            if (!seen.add(candidate.sourceType() + "/" + candidate.sourceId() + "/" + candidate.sourceVersion())) {
                continue;
            }
            Optional<EvidenceSource> source = switch (candidate.sourceType()) {
                case POST -> postSource(casePost, candidate);
                case VERDICT -> verdictSource(casePost, candidate);
                default -> Optional.empty();
            };
            source.filter(s -> usable(s.scope(), targetRoomIds)).ifPresent(sources::add);
        }
        return sources;
    }

    /** 같은 작성자·삭제 안 됨·현재 사건 아님·posts.version 일치. */
    private Optional<EvidenceSource> postSource(PostRow casePost, EvidenceCandidate candidate) {
        return parseUuid(candidate.sourceId())
                .flatMap(queries::findPost)
                .filter(post -> sameAuthorOtherLivePost(casePost, post))
                .filter(post -> post.version() == candidate.sourceVersion())
                .map(post -> {
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("category", post.category());
                    payload.put("amount_krw", post.amountKrw());
                    payload.put("reason", post.reason());
                    payload.put("spent_at", CaseSnapshotAssembler.rfc3339(post.createdAt()));
                    return new EvidenceSource(POST, post.id().toString(), post.version(), payload, scopeOf(post));
                });
    }

    /** 같은 작성자의 다른 게시물 평결·FINAL·verdict_version 일치. scope 는 그 게시물을 따른다. */
    private Optional<EvidenceSource> verdictSource(PostRow casePost, EvidenceCandidate candidate) {
        Optional<VerdictRow> found = parseUuid(candidate.sourceId())
                .flatMap(queries::findVerdict)
                .filter(verdict -> verdict.verdictVersion() == candidate.sourceVersion())
                .filter(verdict -> queries.isFinal(verdict.id()));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        VerdictRow verdict = found.get();
        return queries.findPost(verdict.postId())
                .filter(post -> sameAuthorOtherLivePost(casePost, post))
                .map(post -> {
                    Map<String, Integer> voteCounts = CaseSnapshotAssembler.voteCounts(post.postType(),
                            queries.countVotesByVerdict(post.id()));
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("result", verdict.juryResult());
                    payload.put("guilty_ratio", CaseSnapshotAssembler.guiltyRatio(post.postType(), voteCounts));
                    payload.put("sentence", verdict.sentence());
                    return new EvidenceSource(VERDICT, verdict.id().toString(), verdict.verdictVersion(), payload,
                            scopeOf(post));
                });
    }

    private static boolean sameAuthorOtherLivePost(PostRow casePost, PostRow post) {
        return post.authorId().equals(casePost.authorId())
                && !post.id().equals(casePost.id())
                && post.deletedAt() == null;
    }

    /** public_share_enabled 면 PUBLIC, 아니면 그 게시물 공유 방의 ROOMS. PRIVATE 는 만들지 않는다. */
    private EvidenceScope scopeOf(PostRow post) {
        List<String> roomIds = roomIds(queries.findSharedRooms(post.id()));
        return new EvidenceScope(post.publicShareEnabled() ? Visibility.PUBLIC : Visibility.ROOMS, roomIds);
    }

    /** AI domain/visibility.py usable 과 같다. PUBLIC 은 늘, ROOMS 는 현재 사건 공유 방이 비지 않고 room_ids 의 부분집합일 때. */
    static boolean usable(EvidenceScope scope, Set<String> targetRoomIds) {
        return switch (scope.visibility()) {
            case PUBLIC -> true;
            case ROOMS -> !targetRoomIds.isEmpty() && scope.roomIds().containsAll(targetRoomIds);
            case PRIVATE -> false;
        };
    }

    // --- aggregates(답 1~4, 10 §4.2 반복 집계) ---

    private Aggregates aggregates(PostRow casePost, List<RoomRow> caseRooms) {
        OffsetDateTime createdAt = casePost.createdAt();
        OffsetDateTime monthStart = createdAt.atZoneSameInstant(KST).withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS)
                .toOffsetDateTime();
        long spent = queries.sumSpentAmount(casePost.authorId(), monthStart, createdAt);
        int budget = queries.findMonthlyBudget(casePost.authorId()).orElse(0);
        double burnRate = burnRate(spent, budget);

        OffsetDateTime windowStart = createdAt.minus(WINDOW);
        int repeat = queries.countRepeatSameCategory(casePost.authorId(), casePost.id(), casePost.category(),
                windowStart, createdAt);

        int ruleVersion = caseRooms.stream().mapToInt(RoomRow::ruleVersion).max().orElse(1);

        return new Aggregates(
                burnRate,
                tier(burnRate),
                // 답 3: 서버에 무지출 재료가 없어 늘 0
                0,
                repeat,
                casePost.id().toString(),
                new AggregateWindow(CaseSnapshotAssembler.rfc3339(windowStart),
                        CaseSnapshotAssembler.rfc3339(createdAt)),
                ruleVersion);
    }

    /** 답 1: 이번 KST 달력월 spent 합 ÷ monthly_budget, 0..1 로 자른다. budget ≤ 0 이면 0. */
    static double burnRate(long spentAmount, int monthlyBudget) {
        if (monthlyBudget <= 0) {
            return 0.0;
        }
        return Math.clamp((double) spentAmount / monthlyBudget, 0.0, 1.0);
    }

    /** 답 2: <0.25 king · <0.5 flower · <0.8 hardcore · 그 이상 penniless. 값은 프론트 Tier 4종. */
    static String tier(double burnRate) {
        if (burnRate < 0.25) {
            return "king";
        }
        if (burnRate < 0.5) {
            return "flower";
        }
        if (burnRate < 0.8) {
            return "hardcore";
        }
        return "penniless";
    }

    // --- room_rules(9/14 A안) ---

    private static List<RoomRule> roomRules(List<RoomRow> caseRooms) {
        List<RoomRule> rules = new ArrayList<>();
        for (RoomRow room : caseRooms) {
            for (int i = 0; i < room.rules().size(); i++) {
                rules.add(new RoomRule(room.id().toString(), Integer.toString(i), room.ruleVersion(),
                        room.rules().get(i)));
            }
        }
        return rules;
    }

    // --- recent_verdicts(답 6) ---

    /** 같은 작성자·현재 사건 제외·confirmed_at ∈ [created_at−30일, created_at)·FINAL·sentence 있음·scope 통과, 최근 10건. */
    private List<RecentVerdict> recentVerdicts(PostRow casePost, Set<String> targetRoomIds) {
        OffsetDateTime createdAt = casePost.createdAt();
        List<RecentVerdict> verdicts = new ArrayList<>();
        for (RecentVerdictRow row : queries.findRecentFinalVerdicts(casePost.authorId(), casePost.id(),
                createdAt.minus(WINDOW), createdAt)) {
            if (verdicts.size() == RECENT_VERDICTS_MAX) {
                break;
            }
            Optional<PostRow> post = queries.findPost(row.postId());
            if (post.isEmpty()) {
                continue;
            }
            EvidenceScope scope = scopeOf(post.get());
            if (!usable(scope, targetRoomIds)) {
                continue;
            }
            // verdict_id 는 워커 모델이 알 수 없는 필드로 거부해 넣지 않는다(9/14 결정)
            verdicts.add(new RecentVerdict(row.postId().toString(), row.postVersion(), row.category(),
                    row.amountKrw(), row.reason(), row.juryResult(), row.sentence(),
                    CaseSnapshotAssembler.rfc3339(row.confirmedAt()), scope));
        }
        return verdicts;
    }

    private static List<String> roomIds(List<RoomRow> rooms) {
        return rooms.stream().map(room -> room.id().toString()).toList();
    }

    private static Optional<UUID> parseUuid(String value) {
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
