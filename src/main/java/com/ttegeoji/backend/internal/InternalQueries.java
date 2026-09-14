package com.ttegeoji.backend.internal;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * snapshot·resolve-evidence 조회(10 §4.1·§4.2). 앞 작업의 repository 를 고치지 않으려고 JdbcTemplate 으로 따로 둔다.
 * native enum 컬럼은 ::text 로 읽고, 바인드가 필요하면 CAST(? AS <type>) 로 넣는다. 잠그지 않는다.
 */
@Component
@RequiredArgsConstructor
public class InternalQueries {

    public record PostRow(UUID id, UUID authorId, String postType, int amountKrw, String category, String item,
                          String reason, int version, int audienceVersion, boolean publicShareEnabled,
                          UUID submissionId, OffsetDateTime deletedAt, OffsetDateTime createdAt) {
    }

    public record RoomRow(UUID id, String spiceLevel, int ruleVersion, List<String> rules, OffsetDateTime createdAt) {
    }

    public record VerdictRow(UUID id, UUID postId, int verdictVersion, String juryResult, String policySnapshot,
                             OffsetDateTime confirmedAt, OffsetDateTime deadlineAt, String sentence,
                             String sentenceSource, String sentencingReason, String reasonSource,
                             String appliedIntensity, String targetIntensities, String defaultIntensity) {
    }

    private static final RowMapper<PostRow> POST_ROW = (rs, i) -> new PostRow(
            rs.getObject("id", UUID.class),
            rs.getObject("author_id", UUID.class),
            rs.getString("post_type"),
            rs.getInt("amount_krw"),
            rs.getString("category"),
            rs.getString("item"),
            rs.getString("reason"),
            rs.getInt("version"),
            rs.getInt("audience_version"),
            rs.getBoolean("public_share_enabled"),
            rs.getObject("submission_id", UUID.class),
            rs.getObject("deleted_at", OffsetDateTime.class),
            rs.getObject("created_at", OffsetDateTime.class));

    private static final RowMapper<RoomRow> ROOM_ROW = (rs, i) -> new RoomRow(
            rs.getObject("id", UUID.class),
            rs.getString("spice_level"),
            rs.getInt("rule_version"),
            textArray(rs, "rules"),
            rs.getObject("created_at", OffsetDateTime.class));

    private static final RowMapper<VerdictRow> VERDICT_ROW = (rs, i) -> new VerdictRow(
            rs.getObject("id", UUID.class),
            rs.getObject("post_id", UUID.class),
            rs.getInt("verdict_version"),
            rs.getString("jury_result"),
            rs.getString("policy_snapshot"),
            rs.getObject("confirmed_at", OffsetDateTime.class),
            rs.getObject("deadline_at", OffsetDateTime.class),
            rs.getString("sentence"),
            rs.getString("sentence_source"),
            rs.getString("sentencing_reason"),
            rs.getString("reason_source"),
            rs.getString("applied_intensity"),
            rs.getString("target_intensities"),
            rs.getString("default_intensity"));

    private static final String VERDICT_COLUMNS = """
            id, post_id, verdict_version, jury_result::text AS jury_result, policy_snapshot::text AS policy_snapshot,
            confirmed_at, deadline_at, sentence::text AS sentence, sentence_source, sentencing_reason, reason_source,
            applied_intensity::text AS applied_intensity, target_intensities::text AS target_intensities,
            default_intensity::text AS default_intensity
            """;

    private final JdbcTemplate jdbcTemplate;

    /** 삭제된 게시물도 돌려준다. 삭제 판정은 호출자가 한다. */
    public Optional<PostRow> findPost(UUID postId) {
        return jdbcTemplate.query("""
                        SELECT id, author_id, post_type::text AS post_type, amount_krw, category, item, reason, version,
                               audience_version, public_share_enabled, submission_id, deleted_at, created_at
                          FROM posts
                         WHERE id = ?
                        """, POST_ROW, postId)
                .stream().findFirst();
    }

    /** 게시물이 공유된 방. 만든 순서(같으면 id)로 정렬한다. */
    public List<RoomRow> findSharedRooms(UUID postId) {
        // feat-privacy 의 post_rooms.revoked_at 이 머지되면 AND pr.revoked_at IS NULL 을 더한다(스펙: 컬럼이 없으면 조건 없이)
        return jdbcTemplate.query("""
                SELECT r.id, r.spice_level::text AS spice_level, r.rule_version, r.rules, r.created_at
                  FROM post_rooms pr
                  JOIN rooms r ON r.id = pr.room_id
                 WHERE pr.post_id = ?
                 ORDER BY r.created_at, r.id
                """, ROOM_ROW, postId);
    }

    public Optional<VerdictRow> findVerdict(UUID verdictId) {
        return jdbcTemplate.query("SELECT " + VERDICT_COLUMNS + " FROM verdicts WHERE id = ?", VERDICT_ROW, verdictId)
                .stream().findFirst();
    }

    public Optional<VerdictRow> findVerdictByPostId(UUID postId) {
        return jdbcTemplate.query("SELECT " + VERDICT_COLUMNS + " FROM verdicts WHERE post_id = ?", VERDICT_ROW, postId)
                .stream().findFirst();
    }

    /** votes 를 verdict 값별로 센다. 표가 없는 값은 키가 없다. */
    public Map<String, Integer> countVotesByVerdict(UUID postId) {
        Map<String, Integer> counts = new HashMap<>();
        jdbcTemplate.query("""
                SELECT verdict::text AS verdict, count(*) AS n
                  FROM votes
                 WHERE post_id = ?
                 GROUP BY verdict
                """, rs -> {
            counts.put(rs.getString("verdict"), rs.getInt("n"));
        }, postId);
        return counts;
    }

    /** submissions.intake_result jsonb 문자열. submission 이 없거나 값이 NULL 이면 빈 결과. */
    public Optional<String> findIntakeResult(UUID submissionId) {
        return jdbcTemplate.query("SELECT intake_result::text AS intake_result FROM submissions WHERE id = ?",
                        (rs, i) -> rs.getString("intake_result"), submissionId)
                .stream().filter(java.util.Objects::nonNull).findFirst();
    }

    private static List<String> textArray(ResultSet rs, String column) throws SQLException {
        Array array = rs.getArray(column);
        if (array == null) {
            return List.of();
        }
        return Arrays.asList((String[]) array.getArray());
    }
}
