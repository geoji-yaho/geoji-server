package com.ttegeoji.backend.comment;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 댓글 테스트 픽스처. api·internal 픽스처가 package-private 이라 따로 둔다. 행은 JdbcTemplate 으로 직접 넣는다.
 * verdict 는 FINAL 로만 넣어 JuryScheduler 게이트(PENDING 대상)가 건드리지 않게 한다.
 */
final class CommentFixtures {

    record RetainJob(String dedupeKey, String eventType, String payload) {
    }

    private final JdbcTemplate jdbc;

    CommentFixtures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    UUID profile(String nickname) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO profiles (id, nickname, monthly_budget) VALUES (?, ?, 0)", id, nickname);
        return id;
    }

    UUID profile() {
        return profile("n");
    }

    UUID room(UUID createdBy) {
        return jdbc.queryForObject("""
                INSERT INTO rooms (name, spice_level, vote_deadline_minutes, created_by)
                VALUES ('room', CAST('spicy' AS spice_level), 30, ?) RETURNING id""", UUID.class, createdBy);
    }

    void member(UUID room, UUID user) {
        jdbc.update("INSERT INTO room_members (room_id, user_id) VALUES (?, ?)", room, user);
    }

    /** 마감이 한 시간 뒤라 JuryScheduler 마감 스캔 대상이 아니다 */
    UUID post(UUID author) {
        return jdbc.queryForObject("""
                INSERT INTO posts (author_id, post_type, amount_krw, category, item, reason, intake_status,
                                   intake_source, vote_deadline_at)
                VALUES (?, CAST('spent' AS post_type), 12000, '교통/택시', '택시', '늦잠', 'PASS', 'AI',
                        now() + interval '1 hour')
                RETURNING id""", UUID.class, author);
    }

    void share(UUID post, UUID room) {
        jdbc.update("INSERT INTO post_rooms (post_id, room_id) VALUES (?, ?)", post, room);
    }

    void revoke(UUID post, UUID room) {
        jdbc.update("UPDATE post_rooms SET revoked_at = now() WHERE post_id = ? AND room_id = ?", post, room);
    }

    void deletePost(UUID post) {
        jdbc.update("UPDATE posts SET deleted_at = now() WHERE id = ?", post);
    }

    UUID verdict(UUID post, String juryResult, String sentenceStatus) {
        return jdbc.queryForObject("""
                INSERT INTO verdicts (post_id, jury_result, policy_snapshot, confirmed_at, target_intensities,
                                      default_intensity, sentence_status)
                VALUES (?, CAST(? AS verdict), '{}'::jsonb, now(), '["spicy"]'::jsonb, 'spicy', ?) RETURNING id""",
                UUID.class, post, juryResult, sentenceStatus);
    }

    /** 판결 확정(guilty·FINAL) 게시물 */
    UUID judge(UUID post) {
        return verdict(post, "guilty", "FINAL");
    }

    UUID comment(UUID post, UUID room, UUID user, String content) {
        return jdbc.queryForObject("""
                INSERT INTO post_comments (post_id, room_id, user_id, content) VALUES (?, ?, ?, ?) RETURNING id""",
                UUID.class, post, room, user, content);
    }

    void deleteComment(UUID comment) {
        jdbc.update("UPDATE post_comments SET deleted_at = now() WHERE id = ?", comment);
    }

    Map<String, Object> commentRow(UUID comment) {
        return jdbc.queryForMap("SELECT * FROM post_comments WHERE id = ?", comment);
    }

    OffsetDateTime retainedAt(UUID comment) {
        return jdbc.queryForObject("SELECT retained_at FROM post_comments WHERE id = ?", OffsetDateTime.class, comment);
    }

    List<RetainJob> retainJobs(UUID comment) {
        return jdbc.query("""
                        SELECT dedupe_key, event_type, payload::text AS payload FROM ai.jobs
                         WHERE kind = 'RETAIN' AND aggregate_id = ?""",
                (rs, i) -> new RetainJob(rs.getString("dedupe_key"), rs.getString("event_type"), rs.getString("payload")),
                comment.toString());
    }

    long epoch(String scopeKey) {
        List<Long> rows = jdbc.queryForList("SELECT epoch FROM ai.privacy_epochs WHERE scope_key = ?", Long.class,
                scopeKey);
        return rows.isEmpty() ? 0L : rows.getFirst();
    }

    /** source_id 로 찾은 무효화 기록 (scope_key, source_type) */
    List<List<String>> invalidations(UUID comment) {
        return jdbc.query("SELECT scope_key, source_type FROM privacy_invalidations WHERE source_id = ? ORDER BY id",
                (rs, i) -> List.of(rs.getString("scope_key"), rs.getString("source_type")), comment.toString());
    }
}
