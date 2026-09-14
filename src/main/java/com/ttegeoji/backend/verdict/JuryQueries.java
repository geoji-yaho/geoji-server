package com.ttegeoji.backend.verdict;

import com.ttegeoji.backend.domain.enums.SpiceLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 평결 확정·SENTENCE 게이트가 쓰는 조회(10 §3). 트랜잭션은 호출자가 연다.
 * 잠금 순서(10 §2)는 privacy scope → verdict → job 이고, 이 클래스가 잠그는 것은 posts 행과 verdicts 행뿐이다.
 */
@Component
@RequiredArgsConstructor
public class JuryQueries {

    /** 잠근 게시물. deleted 는 posts.deleted_at IS NOT NULL. */
    public record LockedPost(UUID id, UUID authorId, String postType, OffsetDateTime voteDeadlineAt, boolean deleted) {
    }

    public record InsertedVerdict(UUID id, OffsetDateTime confirmedAt) {
    }

    /** 게이트가 SENTENCE 를 넣을 verdict. */
    public record PendingVerdict(UUID verdictId, UUID postId, int verdictVersion) {
    }

    /** 게이트가 잠근 verdict 행. waitOver = confirmed_at + 30s ≤ DB now()(10 §3 D-24). */
    public record GateRow(UUID verdictId, UUID postId, int verdictVersion, String juryResult, String sentenceStatus,
                          OffsetDateTime deadlineAt, boolean policyValid, boolean waitOver) {
    }

    // 10 §3: 유죄인데 허용 목록이 비었거나 fallback 이 목록에 없으면 정책 설정 오류 — SENTENCE 를 넣지 않는다.
    // CASE 로 감싸 배열이 아닌 값에서 jsonb_array_* 가 오류를 내지 않게 한다
    private static final String POLICY_VALID = """
            (v.jury_result <> 'guilty' OR CASE
                WHEN jsonb_typeof(v.policy_snapshot -> 'allowed_sentences') = 'array'
                THEN EXISTS (SELECT 1 FROM jsonb_array_elements(v.policy_snapshot -> 'allowed_sentences') e
                              WHERE e ->> 'code' = v.policy_snapshot ->> 'fallback_sentence')
                ELSE false END)
            """;

    private final JdbcTemplate jdbcTemplate;

    private volatile Boolean revokedColumnPresent;

    public OffsetDateTime dbNow() {
        return jdbcTemplate.queryForObject("SELECT now()", OffsetDateTime.class);
    }

    /**
     * 같은 게시물의 확정을 직렬화한다(10 §13 마지막 표 동시 도착). FOR UPDATE 가 아니라 FOR NO KEY UPDATE 다 —
     * 표 INSERT 는 posts FK 로 KEY SHARE 를 잡으므로, 두 투표 트랜잭션이 서로의 KEY SHARE 를 기다리는 교착을 피한다.
     */
    public Optional<LockedPost> lockPost(UUID postId) {
        return jdbcTemplate.query("""
                        SELECT id, author_id, post_type::text AS post_type, vote_deadline_at, deleted_at IS NOT NULL AS deleted
                          FROM posts WHERE id = ? FOR NO KEY UPDATE
                        """,
                (rs, i) -> new LockedPost(rs.getObject("id", UUID.class), rs.getObject("author_id", UUID.class),
                        rs.getString("post_type"), rs.getObject("vote_deadline_at", OffsetDateTime.class),
                        rs.getBoolean("deleted")),
                postId).stream().findFirst();
    }

    public boolean verdictExists(UUID postId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM verdicts WHERE post_id = ?)", Boolean.class, postId));
    }

    /** 공유 방(철회되지 않은 것). 생성일 순. */
    public List<JuryTally.SharedRoom> sharedRooms(UUID postId) {
        return jdbcTemplate.query("""
                        SELECT r.id, r.spice_level::text AS spice_level, r.created_at
                          FROM post_rooms pr JOIN rooms r ON r.id = pr.room_id
                         WHERE pr.post_id = ? %s
                         ORDER BY r.created_at, r.id
                        """.formatted(activeShareCondition()),
                (rs, i) -> new JuryTally.SharedRoom(rs.getString("id"), SpiceLevel.valueOf(rs.getString("spice_level")),
                        rs.getObject("created_at", OffsetDateTime.class)),
                postId);
    }

    /** 투표 가능 인원 = 공유 방 멤버 합집합 − 작성자. */
    public int eligibleCount(UUID postId, UUID authorId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT count(DISTINCT rm.user_id)
                  FROM post_rooms pr JOIN room_members rm ON rm.room_id = pr.room_id
                 WHERE pr.post_id = ? AND rm.user_id <> ? %s
                """.formatted(activeShareCondition()), Integer.class, postId, authorId);
        return count == null ? 0 : count;
    }

    /** 투표 가능 인원이 1명 이상이고 그 전원이 표를 냈는가. */
    public boolean allEligibleVoted(UUID postId, UUID authorId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                WITH eligible AS (
                    SELECT DISTINCT rm.user_id
                      FROM post_rooms pr JOIN room_members rm ON rm.room_id = pr.room_id
                     WHERE pr.post_id = ? AND rm.user_id <> ? %s)
                SELECT EXISTS (SELECT 1 FROM eligible)
                   AND NOT EXISTS (SELECT 1 FROM eligible e
                                    WHERE NOT EXISTS (SELECT 1 FROM votes vo
                                                       WHERE vo.post_id = ? AND vo.voter_id = e.user_id))
                """.formatted(activeShareCondition()), Boolean.class, postId, authorId, postId));
    }

    public List<JuryTally.Vote> votes(UUID postId) {
        return jdbcTemplate.query(
                "SELECT verdict::text AS verdict, room_id FROM votes WHERE post_id = ? ORDER BY created_at, id",
                (rs, i) -> new JuryTally.Vote(rs.getString("verdict"), rs.getString("room_id")),
                postId);
    }

    /** vote_deadline_at 이 지났고 verdict 가 없고 삭제되지 않은 게시물. */
    public List<UUID> duePostIds(OffsetDateTime now) {
        return jdbcTemplate.query("""
                        SELECT p.id FROM posts p
                         WHERE p.vote_deadline_at <= ? AND p.deleted_at IS NULL
                           AND NOT EXISTS (SELECT 1 FROM verdicts v WHERE v.post_id = p.id)
                         ORDER BY p.vote_deadline_at
                        """,
                (rs, i) -> rs.getObject("id", UUID.class), now);
    }

    /**
     * verdicts INSERT(10 §3). confirmed_at 은 DB now(), deadline_at 은 NULL(D-24 게이트가 SENTENCE 를 넣을 때 채운다).
     * 같은 post 에 이미 있으면 빈 결과(post_id UNIQUE).
     */
    public Optional<InsertedVerdict> insertVerdict(UUID postId, String juryResult, String policySnapshotJson,
                                                   String targetIntensitiesJson, SpiceLevel defaultIntensity) {
        return jdbcTemplate.query("""
                        INSERT INTO verdicts (post_id, verdict_version, jury_result, policy_snapshot, confirmed_at, deadline_at,
                                              sentence_status, text_status, target_intensities, default_intensity,
                                              applied_intensity)
                        VALUES (?, 1, CAST(? AS verdict), CAST(? AS jsonb), now(), NULL, 'PENDING', 'PENDING',
                                CAST(? AS jsonb), CAST(? AS spice_level), CAST(? AS spice_level))
                        ON CONFLICT (post_id) DO NOTHING
                        RETURNING id, confirmed_at
                        """,
                (rs, i) -> new InsertedVerdict(rs.getObject("id", UUID.class),
                        rs.getObject("confirmed_at", OffsetDateTime.class)),
                postId, juryResult, policySnapshotJson, targetIntensitiesJson, defaultIntensity.name(),
                defaultIntensity.name()).stream().findFirst();
    }

    /** 게이트 보류 중인 verdict: SENTENCE job 없음 ∧ PENDING ∧ deadline_at NULL ∧ dismissed 아님 ∧ 정책 정상. */
    public List<PendingVerdict> pendingVerdicts() {
        return jdbcTemplate.query("""
                        SELECT v.id, v.post_id, v.verdict_version FROM verdicts v
                         WHERE v.sentence_status = 'PENDING' AND v.deadline_at IS NULL AND v.jury_result <> 'dismissed'
                           AND %s
                           AND NOT EXISTS (SELECT 1 FROM ai.jobs j
                                            WHERE j.dedupe_key = 'sentence:' || v.id || ':' || v.verdict_version)
                         ORDER BY v.confirmed_at
                        """.formatted(POLICY_VALID),
                (rs, i) -> new PendingVerdict(rs.getObject("id", UUID.class), rs.getObject("post_id", UUID.class),
                        rs.getInt("verdict_version")));
    }

    /** verdict 행을 잠그고 게이트 판정에 필요한 값을 읽는다. */
    public Optional<GateRow> lockVerdictForGate(UUID verdictId) {
        return jdbcTemplate.query("""
                        SELECT v.id, v.post_id, v.verdict_version, v.jury_result::text AS jury_result, v.sentence_status,
                               v.deadline_at, %s AS policy_valid,
                               v.confirmed_at + interval '30 seconds' <= now() AS wait_over
                          FROM verdicts v WHERE v.id = ? FOR UPDATE
                        """.formatted(POLICY_VALID),
                (rs, i) -> new GateRow(rs.getObject("id", UUID.class), rs.getObject("post_id", UUID.class),
                        rs.getInt("verdict_version"), rs.getString("jury_result"), rs.getString("sentence_status"),
                        rs.getObject("deadline_at", OffsetDateTime.class), rs.getBoolean("policy_valid"),
                        rs.getBoolean("wait_over")),
                verdictId).stream().findFirst();
    }

    public void setDeadline(UUID verdictId, OffsetDateTime deadlineAt) {
        jdbcTemplate.update("UPDATE verdicts SET deadline_at = ? WHERE id = ?", deadlineAt, verdictId);
    }

    // post_rooms.revoked_at 은 feat-privacy 004b 가 더한다. 컬럼이 있을 때만 철회된 공유를 뺀다
    private String activeShareCondition() {
        Boolean present = revokedColumnPresent;
        if (present == null) {
            present = Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM information_schema.columns
                                    WHERE table_name = 'post_rooms' AND column_name = 'revoked_at'
                                      AND table_schema = ANY (current_schemas(false)))
                    """, Boolean.class));
            revokedColumnPresent = present;
        }
        return present ? "AND pr.revoked_at IS NULL" : "";
    }
}
