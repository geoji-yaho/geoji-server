package com.ttegeoji.backend.jobs;

import com.ttegeoji.backend.domain.enums.SpiceLevel;
import com.ttegeoji.backend.util.Json;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 업무 트랜잭션 안에서 ai.jobs 를 넣는다(10 §3). 트랜잭션 밖에서 부르면 IllegalTransactionStateException —
 * job 만 커밋되고 업무 변경이 롤백되는 일을 막는다. 업무 테이블은 읽지 않고 호출자가 준 id·version 만 쓴다.
 * 모든 INSERT 는 ON CONFLICT (dedupe_key) DO NOTHING 이고, 충돌이면 기존 행의 id·deadline_at 을 돌려준다.
 * 호출자 트랜잭션은 READ COMMITTED(기본)를 전제한다. 동시 트랜잭션이 같은 dedupe 를 먼저 커밋했을 때 다시 읽는 SELECT 가
 * 그 행을 보려면 문장마다 새 스냅샷이어야 한다. 마감의 now() 는 트랜잭션 시작 시각이므로 업무 트랜잭션을 짧게 유지한다.
 */
@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class JobEnqueuer {

    private static final RowMapper<EnqueuedJob> CREATED =
            (rs, i) -> new EnqueuedJob(rs.getObject("id", UUID.class),
                    rs.getObject("deadline_at", OffsetDateTime.class), true);
    private static final RowMapper<EnqueuedJob> EXISTING =
            (rs, i) -> new EnqueuedJob(rs.getObject("id", UUID.class),
                    rs.getObject("deadline_at", OffsetDateTime.class), false);

    private final JdbcTemplate jdbcTemplate;

    public EnqueuedJob enqueuePrepare(String postId, long postVersion, long audienceVersion) {
        return enqueuePrepare(postId, postVersion, audienceVersion, null);
    }

    public EnqueuedJob enqueuePrepare(String postId, long postVersion, long audienceVersion, String traceId) {
        return insert(JobKind.PREPARE, JobKind.EVENT_POST_CREATED,
                "prepare:" + postId + ":" + postVersion + ":" + audienceVersion,
                postId, postVersion, JobPayloads.prepare(postId, postVersion, audienceVersion), traceId);
    }

    /** deadline_at = INSERT 시각(DB now()) + kind 별 초(10 §3 D-24, 값은 {@link JobKind}). 반환값의 deadlineAt 을 verdicts.deadline_at 에 같이 쓴다. */
    public EnqueuedJob enqueueSentence(String verdictId, long verdictVersion, String postId) {
        return enqueueSentence(verdictId, verdictVersion, postId, null);
    }

    public EnqueuedJob enqueueSentence(String verdictId, long verdictVersion, String postId, String traceId) {
        return insert(JobKind.SENTENCE, JobKind.EVENT_VERDICT_CONFIRMED,
                "sentence:" + verdictId + ":" + verdictVersion,
                verdictId, verdictVersion, JobPayloads.sentence(verdictId, verdictVersion, postId), traceId);
    }

    /** version = verdict_version(10 §3 9/14 코드 대조). */
    public EnqueuedJob enqueueRetainVerdict(String verdictId, long version) {
        return enqueueRetainVerdict(verdictId, version, null);
    }

    public EnqueuedJob enqueueRetainVerdict(String verdictId, long version, String traceId) {
        return insert(JobKind.RETAIN, JobKind.EVENT_SENTENCE_FINALIZED,
                "retain:verdict:" + verdictId + ":" + version,
                verdictId, version, JobPayloads.retainVerdict(verdictId, version), traceId);
    }

    public EnqueuedJob enqueueRetainComment(String commentId, long version) {
        return enqueueRetainComment(commentId, version, null);
    }

    public EnqueuedJob enqueueRetainComment(String commentId, long version, String traceId) {
        return insert(JobKind.RETAIN, JobKind.EVENT_COMMENT_APPROVED,
                "retain:comment:" + commentId + ":" + version,
                commentId, version, JobPayloads.retainComment(commentId, version), traceId);
    }

    /** intensities 는 null(전체 강도) 또는 1개 이상. deadline_at = INSERT 시각 + 20s. */
    public EnqueuedJob enqueueTextRetry(String verdictId, long verdictVersion, int round, List<SpiceLevel> intensities) {
        return enqueueTextRetry(verdictId, verdictVersion, round, intensities, null);
    }

    public EnqueuedJob enqueueTextRetry(String verdictId, long verdictVersion, int round, List<SpiceLevel> intensities,
                                        String traceId) {
        // 빈 목록 검사를 INSERT 전에 한다(JobPayloads 가 던진다)
        Map<String, Object> payload = JobPayloads.textRetry(verdictId, verdictVersion, round, intensities);
        return insert(JobKind.TEXT_RETRY, JobKind.EVENT_VERDICT_TEXT_RETRY,
                "text-retry:" + verdictId + ":" + verdictVersion + ":" + round,
                verdictId, verdictVersion, payload, traceId);
    }

    /**
     * 19 §3 게시물 저장 트랜잭션에서 봇이 멤버인 공유 방마다 1개. dedupe 는 post·room·voter 조합이라
     * 같은 글이 같은 방에 다시 INSERT 돼도 1개다. aggregate 는 PREPARE 와 같이 post_id / post_version.
     */
    public EnqueuedJob enqueueJuryVote(String postId, long postVersion, String roomId, String voterId) {
        return insert(JobKind.JURY_VOTE, JobKind.EVENT_JURY_VOTE_REQUESTED,
                "jury-vote:" + postId + ":" + roomId + ":" + voterId,
                postId, postVersion, JobPayloads.juryVote(postId, postVersion, roomId, voterId), null);
    }

    private EnqueuedJob insert(JobKind kind, String eventType, String dedupeKey, String aggregateId,
                               long aggregateVersion, Map<String, Object> payload, String traceId) {
        // deadline 식은 enum 상수에서만 만든다(입력값이 SQL 에 섞이지 않는다). now() 는 10 §3 SQL 그대로 DB 시각
        String deadline = kind.deadlineAfterSeconds() == null
                ? "NULL"
                : "now() + interval '" + kind.deadlineAfterSeconds() + " seconds'";
        String sql = """
                INSERT INTO ai.jobs (id, event_id, event_type, kind, dedupe_key, aggregate_id, aggregate_version,
                                     schema_version, payload, priority, max_attempts, deadline_at, trace_id)
                VALUES (gen_random_uuid(), gen_random_uuid(), ?, ?, ?, ?, ?, 1, CAST(? AS jsonb), ?, ?, %s, ?)
                ON CONFLICT (dedupe_key) DO NOTHING
                RETURNING id, deadline_at
                """.formatted(deadline);
        List<EnqueuedJob> created = jdbcTemplate.query(sql, CREATED,
                eventType, kind.name(), dedupeKey, aggregateId, aggregateVersion, Json.write(payload),
                kind.priority(), kind.maxAttempts(), traceId != null ? traceId : UUID.randomUUID().toString());
        if (!created.isEmpty()) {
            return created.getFirst();
        }
        return jdbcTemplate.queryForObject(
                "SELECT id, deadline_at FROM ai.jobs WHERE dedupe_key = ?", EXISTING, dedupeKey);
    }
}
