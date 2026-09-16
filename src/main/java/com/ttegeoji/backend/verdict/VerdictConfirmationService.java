package com.ttegeoji.backend.verdict;

import com.ttegeoji.backend.domain.enums.Sentence;
import com.ttegeoji.backend.domain.enums.SpiceLevel;
import com.ttegeoji.backend.domain.enums.VerdictType;
import com.ttegeoji.backend.util.Json;
import com.ttegeoji.backend.verdict.JuryQueries.InsertedVerdict;
import com.ttegeoji.backend.verdict.JuryQueries.LockedPost;
import com.ttegeoji.backend.verdict.JuryQueries.PendingVerdict;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.DoubleFunction;
import java.util.stream.Collectors;

/**
 * 10 §3 배심원 평결 확정. 전원 투표 즉시(onVoteCast) 또는 마감 스캔(confirmDue).
 * 한 트랜잭션에서 게시물 잠금 → 집계 → verdicts INSERT → SENTENCE 게이트. job 은 verdict 와 같이 커밋된다(10 §13 commit 직후 중단).
 * policy_snapshot 은 유죄면 유죄율 밴드, 무죄·동의·기각이면 최저 밴드, 각하면 jsonb null.
 * dismissed 는 verdict 만 저장하고, 유죄인데 정책이 비었거나 fallback 이 목록에 없으면 job 없이 ERROR 로그를 남긴다.
 */
@Slf4j
@Service
public class VerdictConfirmationService {

    private final JuryQueries juryQueries;
    private final SentenceGate sentenceGate;
    private final TransactionTemplate transactionTemplate;

    // 테스트가 정책 설정 오류를 재현하려고 바꿔 끼운다
    private volatile DoubleFunction<SentencingPolicy.Snapshot> policyForGuiltyRatio = SentencingPolicy::forGuiltyRatio;

    public VerdictConfirmationService(JuryQueries juryQueries, SentenceGate sentenceGate,
                                      PlatformTransactionManager transactionManager) {
        this.juryQueries = juryQueries;
        this.sentenceGate = sentenceGate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    private enum Trigger { ALL_VOTED, DEADLINE }

    /**
     * 표가 저장된 뒤 부른다. 투표 가능 인원 전원이 표를 냈으면 확정한다. 투표 트랜잭션 안에서 부르면 거기에 합류한다.
     *
     * @return 이번 호출로 확정했으면 true
     */
    @Transactional
    public boolean onVoteCast(UUID postId, UUID roomId) {
        return confirm(postId, roomId, Trigger.ALL_VOTED, null);
    }

    /**
     * vote_deadline_at ≤ now 인 미확정 게시물을 하나씩 확정한다. 게시물마다 트랜잭션이 따로라 하나가 실패해도 나머지는 진행한다.
     *
     * @return 확정한 게시물 수
     */
    public int confirmDue(OffsetDateTime now) {
        int confirmed = 0;
        for (JuryQueries.DueCase due : juryQueries.dueCases(now)) {
            try {
                if (Boolean.TRUE.equals(transactionTemplate.execute(
                        s -> confirm(due.postId(), due.roomId(), Trigger.DEADLINE, now)))) {
                    confirmed++;
                }
            } catch (RuntimeException e) {
                log.error("10 §3 마감 확정 실패 post={} room={}", due.postId(), due.roomId(), e);
            }
        }
        return confirmed;
    }

    void usePolicy(DoubleFunction<SentencingPolicy.Snapshot> policy) {
        this.policyForGuiltyRatio = policy;
    }

    void resetPolicy() {
        this.policyForGuiltyRatio = SentencingPolicy::forGuiltyRatio;
    }

    private boolean confirm(UUID postId, UUID roomId, Trigger trigger, OffsetDateTime now) {
        Optional<LockedPost> locked = juryQueries.lockPost(postId);
        if (locked.isEmpty()) {
            return false;
        }
        LockedPost post = locked.get();
        if (post.deleted() || juryQueries.verdictExists(postId, roomId)) {
            return false;
        }
        boolean ready = switch (trigger) {
            case ALL_VOTED -> juryQueries.allEligibleVoted(postId, roomId, post.authorId());
            case DEADLINE -> !post.voteDeadlineAt().isAfter(now);
        };
        if (!ready) {
            return false;
        }

        // 공유가 철회된 방이면 빈 결과다. 그 방 재판은 열지 않는다
        Optional<JuryTally.SharedRoom> shared = juryQueries.sharedRoom(postId, roomId);
        if (shared.isEmpty()) {
            return false;
        }
        List<JuryTally.SharedRoom> rooms = List.of(shared.get());
        // 그 방에서 들어온 표만 센다
        List<JuryTally.Vote> votes = juryQueries.votes(postId, roomId);
        // 가능 인원 0 이면 JuryTally 가 받지 않는다. 1 로 두어도 표 0 → dismissed 로 결과가 같다
        int eligible = Math.max(1, juryQueries.eligibleCount(postId, roomId, post.authorId()));
        JuryTally.Result tally = JuryTally.tally(post.postType(), votes, eligible, rooms);

        // 유죄는 유죄율 밴드. 무죄·동의·기각은 최저 밴드 객체(AI case-snapshot jury.policy 가 필수 객체·minItems 1, 사용자 9/15).
        // 각하는 job 이 없으니 jsonb null
        SentencingPolicy.Snapshot policy = switch (tally.result()) {
            case guilty -> policyForGuiltyRatio.apply(tally.guiltyRatio());
            case dismissed -> null;
            default -> SentencingPolicy.forGuiltyRatio(0.5);
        };
        String policyError = tally.result() == VerdictType.guilty ? policyError(policy) : null;

        Optional<InsertedVerdict> inserted = juryQueries.insertVerdict(postId, roomId, tally.result().name(),
                policy == null ? "null" : Json.write(policyJson(policy)),
                Json.write(tally.targetIntensities().stream().map(SpiceLevel::name).toList()),
                tally.defaultIntensity());
        if (inserted.isEmpty()) {
            return false;
        }
        UUID verdictId = inserted.get().id();

        if (tally.result() == VerdictType.dismissed) {
            // 10 §3 정족수 미달 각하는 선고 작업을 만들지 않는다
            return true;
        }
        if (policyError != null) {
            log.error("10 §3 정책 설정 오류 — SENTENCE job 을 만들지 않는다 post={} verdict={} version={} 사유={}",
                    postId, verdictId, policy.version(), policyError);
            return true;
        }
        sentenceGate.tryInsert(new PendingVerdict(verdictId, postId, 1));
        return true;
    }

    private static String policyError(SentencingPolicy.Snapshot policy) {
        if (policy.allowedSentences().isEmpty()) {
            return "허용 목록이 비었다";
        }
        try {
            Sentence fallback = Sentence.valueOf(policy.fallbackSentence());
            boolean listed = false;
            for (SentencingPolicy.AllowedSentence allowed : policy.allowedSentences()) {
                if (Sentence.valueOf(allowed.code()) == fallback) {
                    listed = true;
                }
            }
            return listed ? null : "fallback_sentence 가 허용 목록에 없다";
        } catch (IllegalArgumentException | NullPointerException e) {
            return "모르는 형량 코드";
        }
    }

    // 10 §2 policy_snapshot 모양 {version, allowed_sentences[{code, rank}], fallback_sentence, reason_required}
    private static Map<String, Object> policyJson(SentencingPolicy.Snapshot policy) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("version", policy.version());
        json.put("allowed_sentences", policy.allowedSentences().stream()
                .map(a -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("code", a.code());
                    entry.put("rank", a.rank());
                    return entry;
                })
                .toList());
        json.put("fallback_sentence", policy.fallbackSentence());
        json.put("reason_required", policy.reasonRequired());
        return json;
    }
}
