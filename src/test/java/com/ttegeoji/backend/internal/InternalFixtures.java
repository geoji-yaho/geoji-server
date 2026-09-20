package com.ttegeoji.backend.internal;

import com.ttegeoji.backend.jobs.JobKind;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/**
 * snapshot·resolve 테스트 행 삽입. FK 순서 profiles → rooms → submissions → posts → post_rooms → votes → verdicts → ai.jobs.
 * ai.jobs 삽입은 jobs/JobRowFixtures(package-private)와 같은 SQL 이다.
 */
final class InternalFixtures {

    // AI 저장소 contracts/fixtures/case-snapshot-taxi.json jury.policy 모양
    static final String POLICY_JSON = """
            {"version": "sentencing-band-v1",
             "allowed_sentences": [{"code": "probation", "rank": 1}, {"code": "oneDay", "rank": 2}],
             "fallback_sentence": "oneDay", "reason_required": true}""";

    // 비유죄(notGuilty·agree·disagree)도 최저 밴드 정책 객체를 저장한다(feat-jury, 9/15 답 11)
    static final String NON_GUILTY_POLICY_JSON = """
            {"version": "sentencing-band-v1",
             "allowed_sentences": [{"code": "probation", "rank": 1}],
             "fallback_sentence": "probation", "reason_required": true}""";

    private final JdbcTemplate jdbc;

    InternalFixtures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    UUID profile() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO profiles (id, nickname, monthly_budget) VALUES (?, 'tester', 300000)", id);
        return id;
    }

    UUID room(UUID createdBy, String spiceLevel, int ruleVersion, String... rules) {
        UUID id = UUID.randomUUID();
        jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    INSERT INTO rooms (id, name, spice_level, vote_deadline_minutes, rules, created_by, rule_version)
                    VALUES (?, 'room', CAST(? AS spice_level), 60, ?, ?, ?)
                    """);
            ps.setObject(1, id);
            ps.setString(2, spiceLevel);
            ps.setArray(3, con.createArrayOf("text", rules));
            ps.setObject(4, createdBy);
            ps.setInt(5, ruleVersion);
            return ps;
        });
        return id;
    }

    UUID submission(UUID actor, String intakeResultJson) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO submissions (id, actor_id, payload_hash, expires_at, intake_result)
                VALUES (?, ?, 'hash', now() + interval '1 hour', CAST(? AS jsonb))
                """, id, actor, intakeResultJson);
        return id;
    }

    UUID post(UUID author, String postType) {
        return post(author, postType, "늦잠 자서 택시 탐", null);
    }

    /** created_at 을 정한 게시물. createdAt 은 timestamptz 문자열("2026-09-15 12:00:00+09"). */
    UUID post(UUID author, String postType, String category, int amountKrw, String createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO posts (id, author_id, post_type, amount_krw, category, item, reason, intake_status,
                                   intake_source, vote_deadline_at, created_at)
                VALUES (?, ?, CAST(? AS post_type), ?, ?, '품목', '사유', 'PASS', 'AI',
                        CAST(? AS timestamptz) + interval '1 hour', CAST(? AS timestamptz))
                """, id, author, postType, amountKrw, category, createdAt, createdAt);
        return id;
    }

    void setMonthlyBudget(UUID profileId, int budget) {
        jdbc.update("UPDATE profiles SET monthly_budget = ? WHERE id = ?", budget, profileId);
    }

    void setPostVersion(UUID postId, int version) {
        jdbc.update("UPDATE posts SET version = ? WHERE id = ?", version, postId);
    }

    UUID post(UUID author, String postType, String reason, UUID submissionId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO posts (id, author_id, post_type, amount_krw, category, item, reason, intake_status,
                                   intake_source, submission_id, vote_deadline_at)
                VALUES (?, ?, CAST(? AS post_type), 12000, '교통/택시', '택시', ?, 'PASS', 'AI', ?,
                        now() + interval '1 hour')
                """, id, author, postType, reason, submissionId);
        return id;
    }

    void deletePost(UUID postId) {
        jdbc.update("UPDATE posts SET deleted_at = now() WHERE id = ?", postId);
    }

    void share(UUID postId, UUID roomId) {
        jdbc.update("INSERT INTO post_rooms (post_id, room_id) VALUES (?, ?)", postId, roomId);
    }

    void vote(UUID postId, UUID roomId, String verdict) {
        jdbc.update("""
                INSERT INTO votes (post_id, voter_id, room_id, verdict, reason)
                VALUES (?, ?, ?, CAST(? AS verdict), '투표 사유')
                """, postId, profile(), roomId, verdict);
    }

    UUID verdict(UUID postId, String juryResult) {
        return verdict(postId, juryResult, null);
    }

    /** confirmedAt 이 null 이면 now(). */
    UUID verdict(UUID postId, String juryResult, String confirmedAt) {
        UUID id = UUID.randomUUID();
        String policy = "guilty".equals(juryResult) ? POLICY_JSON : NON_GUILTY_POLICY_JSON;
        jdbc.update("""
                INSERT INTO verdicts (id, post_id, jury_result, policy_snapshot, confirmed_at, deadline_at,
                                      target_intensities, default_intensity)
                VALUES (?, ?, CAST(? AS verdict), CAST(? AS jsonb), coalesce(CAST(? AS timestamptz), now()),
                        now() + interval '10 minutes', CAST('["mild", "spicy"]' AS jsonb), 'spicy')
                """, id, postId, juryResult, policy, confirmedAt);
        return id;
    }

    void finalizeVerdict(UUID verdictId, String sentence, String sentenceSource, String sentencingReason,
                         String reasonSource, String appliedIntensity) {
        jdbc.update("""
                UPDATE verdicts
                   SET sentence_status = 'FINAL', sentence = CAST(? AS sentence), sentence_source = ?,
                       sentencing_reason = ?, reason_source = ?, applied_intensity = CAST(? AS spice_level)
                 WHERE id = ?
                """, sentence, sentenceSource, sentencingReason, reasonSource, appliedIntensity, verdictId);
    }

    void epoch(String scopeKey, long epoch) {
        jdbc.update("INSERT INTO ai.privacy_epochs (scope_key, epoch) VALUES (?, ?)", scopeKey, epoch);
    }

    /** RUNNING job. leaseExpr 는 SQL 식("now() + interval '30 seconds'"). */
    UUID runningJob(JobKind kind, String payloadJson, UUID generationId, String leaseExpr) {
        return insertJob(kind, "RUNNING", payloadJson, generationId, leaseExpr);
    }

    UUID job(JobKind kind, String status, String payloadJson) {
        return insertJob(kind, status, payloadJson, null, null);
    }

    private UUID insertJob(JobKind kind, String status, String payloadJson, UUID generationId, String leaseExpr) {
        UUID id = UUID.randomUUID();
        boolean running = "RUNNING".equals(status);
        jdbc.update("""
                INSERT INTO ai.jobs (id, event_id, event_type, kind, dedupe_key, aggregate_id, aggregate_version,
                                     payload, status, priority, attempts, max_attempts, lease_until, owner_id,
                                     generation_id, trace_id)
                VALUES (?, gen_random_uuid(), 'test.event', ?, ?, 'agg', 1, CAST(? AS jsonb), ?, 10, 0, 2, %s, ?, ?,
                        'trace')
                """.formatted(leaseExpr == null ? "NULL" : leaseExpr),
                id, kind.name(), "test:" + id, payloadJson, status,
                running ? "worker-1" : null,
                running ? generationId : null);
        return id;
    }

    static String preparePayload(UUID postId) {
        return "{\"post_id\": \"" + postId + "\", \"post_version\": 1, \"audience_version\": 1}";
    }

    /** 19 §3 JURY_VOTE payload. voter 는 떼거지봇 id */
    static String juryVotePayload(UUID postId, UUID roomId, UUID voterId) {
        return "{\"post_id\": \"" + postId + "\", \"post_version\": 1, \"room_id\": \"" + roomId
                + "\", \"voter_id\": \"" + voterId + "\"}";
    }

    /** application-test.yml 의 geoji.ai-juror-user-id 와 같은 값. profiles 행을 한 번만 넣고 그 id 를 돌려준다(19 §2) */
    static final UUID AI_JUROR = UUID.fromString("00000000-0000-4000-8000-0000000a1b0c");

    UUID aiJuror() {
        jdbc.update("INSERT INTO profiles (id, nickname, monthly_budget) VALUES (?, '떼거지봇', 300000) ON CONFLICT (id) DO NOTHING",
                AI_JUROR);
        return AI_JUROR;
    }

    void member(UUID roomId, UUID userId) {
        jdbc.update("INSERT INTO room_members (room_id, user_id) VALUES (?, ?) ON CONFLICT DO NOTHING", roomId, userId);
    }

    static String sentencePayload(UUID verdictId, UUID postId) {
        return "{\"verdict_id\": \"" + verdictId + "\", \"verdict_version\": 1, \"post_id\": \"" + postId + "\"}";
    }

    static String textRetryPayload(UUID verdictId) {
        return "{\"verdict_id\": \"" + verdictId + "\", \"verdict_version\": 1, \"round\": 1}";
    }

    static String retainVerdictPayload(UUID verdictId) {
        return "{\"event\": \"sentence.finalized\", \"verdict_id\": \"" + verdictId
                + "\", \"comment_id\": null, \"version\": 1}";
    }

    static String retainCommentPayload(UUID commentId) {
        return "{\"event\": \"comment.approved\", \"verdict_id\": null, \"comment_id\": \"" + commentId
                + "\", \"version\": 1}";
    }
}
