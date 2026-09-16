package com.ttegeoji.backend.privacy;

import com.ttegeoji.backend.domain.enums.SpiceLevel;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 10 §8 무효화용 조회·갱신. 트랜잭션은 호출자가 연다.
 * invalidate_scope.sql 은 AI 저장소 database/sql/invalidate_scope.sql @ 72dac76ba7a1b4d8c017fa4ef73916c62511cbe2 복사본이다(10 §16.3).
 */
@Component
public class InvalidationQueries {

    static final String INVALIDATE_SCOPE_RESOURCE = "sql/invalidate_scope.sql";

    // 주석 안에 `?`·`:scope_key`·`;` 가 있어 파라미터 파서에 넘기기 전에 지운다(AI postgres_memory.invalidate_statements 와 같은 규칙)
    private static final Pattern LINE_COMMENT = Pattern.compile("--[^\\n]*");

    public record PendingInvalidation(long id, String scopeKey, String sourceType, String sourceId, int attempts) {
    }

    public record FailureRecorded(long id, String scopeKey, int attempts, String status) {
    }

    public record AffectedVerdictText(UUID id, UUID verdictId, SpiceLevel intensity, long textVersion) {
    }

    private static final RowMapper<PendingInvalidation> PENDING = (rs, i) -> new PendingInvalidation(
            rs.getLong("id"), rs.getString("scope_key"), rs.getString("source_type"), rs.getString("source_id"),
            rs.getInt("attempts"));

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final List<String> invalidateStatements;

    public InvalidationQueries(JdbcTemplate jdbc, NamedParameterJdbcTemplate named) {
        this.jdbc = jdbc;
        this.named = named;
        this.invalidateStatements = splitStatements(loadInvalidateScopeSql());
    }

    // ---- 삭제·철회 트랜잭션(InvalidationService) ----

    public boolean isPostAuthor(UUID postId, UUID actorId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM posts WHERE id = ? AND author_id = ?)", Boolean.class, postId, actorId));
    }

    /** backend 에 DELETE 권한이 없어 표시만 한다(004 grants) */
    public void markPostDeleted(UUID postId) {
        jdbc.update("UPDATE posts SET deleted_at = now() WHERE id = ?", postId);
    }

    /** 행은 남기고 revoked_at 으로 표시한다(004b). @return 바뀐 행 수 */
    public int revokeRoomShare(UUID postId, UUID roomId) {
        return jdbc.update("UPDATE post_rooms SET revoked_at = now() WHERE post_id = ? AND room_id = ? AND revoked_at IS NULL",
                postId, roomId);
    }

    public void bumpAudienceVersion(UUID postId) {
        jdbc.update("UPDATE posts SET audience_version = audience_version + 1 WHERE id = ?", postId);
    }

    /** JobQueries.cancelActiveForPost 가 TEXT_RETRY 를 찾는 데 쓴다. verdicts.post_id 는 UNIQUE */
    public List<String> findVerdictIds(UUID postId) {
        return jdbc.queryForList("SELECT id::text FROM verdicts WHERE post_id = ?", String.class, postId);
    }

    public boolean isPostDeleted(UUID postId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT deleted_at IS NOT NULL FROM posts WHERE id = ?", Boolean.class, postId));
    }

    public void insertPending(String scopeKey, String sourceType, String sourceId) {
        jdbc.update("INSERT INTO privacy_invalidations (scope_key, source_type, source_id) VALUES (?, ?, ?)",
                scopeKey, sourceType, sourceId);
    }

    // ---- 스케줄러 ----

    /** 다른 트랜잭션이 잡고 있는 행은 건너뛴다 */
    public List<Long> claimPendingIds(int limit) {
        return jdbc.queryForList("""
                SELECT id FROM privacy_invalidations
                 WHERE status = 'PENDING'
                 ORDER BY created_at, id
                 LIMIT ?
                   FOR UPDATE SKIP LOCKED""", Long.class, limit);
    }

    /** 아직 PENDING 이고 다른 트랜잭션이 잡지 않았을 때만 잠가 돌려준다 */
    public Optional<PendingInvalidation> lockPending(long id) {
        return jdbc.query("""
                SELECT id, scope_key, source_type, source_id, attempts FROM privacy_invalidations
                 WHERE id = ? AND status = 'PENDING'
                   FOR UPDATE SKIP LOCKED""", PENDING, id).stream().findFirst();
    }

    /** invalidate_scope.sql 5문장을 호출자 트랜잭션에서 차례로 실행한다 */
    public void runInvalidateScope(String sourceType, String sourceId, String scopeKey) {
        Map<String, Object> params = Map.of("t", sourceType, "id", sourceId, "scope_key", scopeKey);
        for (String statement : invalidateStatements) {
            named.update(statement, params);
        }
    }

    /**
     * 무효화된 evidence(출처가 (t, id))를 현재 text_version 으로 인용한 verdict_texts.
     * 이번 UPDATE 결과가 아니라 invalidated_at 으로 다시 찾으므로 재실행해도 같은 집합이다.
     */
    public List<AffectedVerdictText> findAffectedVerdictTexts(String sourceType, String sourceId) {
        return jdbc.query("""
                SELECT DISTINCT vt.id, vt.verdict_id, vt.intensity::text AS intensity, vt.text_version
                  FROM ai.evidence_sources es
                  JOIN ai.evidence e ON e.id = es.evidence_id AND e.invalidated_at IS NOT NULL
                  JOIN ai.text_evidence_refs r ON r.evidence_id = e.id
                  JOIN verdict_texts vt ON vt.verdict_id = r.verdict_id
                                       AND vt.intensity::text = r.intensity
                                       AND vt.text_version = r.text_version
                 WHERE es.source_type = ? AND es.source_id = ?
                 ORDER BY vt.verdict_id, intensity""",
                (rs, i) -> new AffectedVerdictText(rs.getObject("id", UUID.class), rs.getObject("verdict_id", UUID.class),
                        SpiceLevel.valueOf(rs.getString("intensity")), rs.getLong("text_version")),
                sourceType, sourceId);
    }

    /** 템플릿 전환에 필요한 verdict 값. sentence 는 무죄 계열이면 null */
    public record VerdictForTemplate(UUID id, UUID postId, UUID roomId, String juryResult, String sentence,
                                     long textVersion) {
    }

    /** id 오름차순으로 verdict 행을 잠근다(10 §2 verdict 단계) */
    public List<VerdictForTemplate> lockVerdictsForTemplate(List<UUID> verdictIds) {
        return jdbc.query("""
                SELECT id, post_id, room_id, jury_result::text AS jury_result, sentence::text AS sentence, text_version
                  FROM verdicts
                 WHERE id = ANY(?)
                 ORDER BY id
                   FOR UPDATE""",
                (rs, i) -> new VerdictForTemplate(rs.getObject("id", UUID.class), rs.getObject("post_id", UUID.class),
                        rs.getObject("room_id", UUID.class), rs.getString("jury_result"), rs.getString("sentence"),
                        rs.getLong("text_version")),
                (Object) verdictIds.toArray(UUID[]::new));
    }

    /** 인용한 강도 행만 템플릿 문구로. 저장 당시 dossier·epoch 스냅샷은 없앤다(VerdictFallbackService 와 같다) */
    public void replaceTextWithTemplate(UUID verdictTextId, String headline, String statementJson, long textVersion) {
        jdbc.update("""
                UPDATE verdict_texts
                   SET headline = ?, statement = CAST(? AS jsonb), source = 'TEMPLATE', text_version = ?,
                       dossier_id = NULL, privacy_epoch_snapshot = NULL
                 WHERE id = ?""", headline, statementJson, textVersion, verdictTextId);
    }

    /** 형량(sentence·sentencing_reason·reason_source)은 건드리지 않는다 */
    public void markVerdictTemplateReady(UUID verdictId, long textVersion) {
        jdbc.update("UPDATE verdicts SET text_version = ?, text_status = 'TEMPLATE_READY' WHERE id = ?",
                textVersion, verdictId);
    }

    public void markDone(long id) {
        jdbc.update("UPDATE privacy_invalidations SET status = 'DONE', processed_at = now() WHERE id = ?", id);
    }

    /**
     * attempts +1. maxAttempts 에 닿으면 FAILED. 이미 다른 인스턴스가 끝낸 행이면 빈 결과.
     *
     * @param lastError 예외 클래스·SQLState 만. 원문을 넣지 않는다
     */
    public Optional<FailureRecorded> recordFailure(long id, String lastError, int maxAttempts) {
        return jdbc.query("""
                UPDATE privacy_invalidations
                   SET attempts = attempts + 1,
                       last_error = ?,
                       status = CASE WHEN attempts + 1 >= ? THEN 'FAILED' ELSE 'PENDING' END
                 WHERE id = ? AND status = 'PENDING'
                RETURNING id, scope_key, attempts, status""",
                (rs, i) -> new FailureRecorded(rs.getLong("id"), rs.getString("scope_key"), rs.getInt("attempts"),
                        rs.getString("status")),
                lastError, maxAttempts, id).stream().findFirst();
    }

    // ---- SQL 파일 ----

    static String loadInvalidateScopeSql() {
        try {
            return new ClassPathResource(INVALIDATE_SCOPE_RESOURCE).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("무효화 SQL 을 읽지 못했습니다: " + INVALIDATE_SCOPE_RESOURCE, e);
        }
    }

    /** 주석 제거 → `;` 분할 → trim → 빈 문장 제외 */
    static List<String> splitStatements(String sql) {
        String withoutComments = LINE_COMMENT.matcher(sql).replaceAll("");
        return Arrays.stream(withoutComments.split(";"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    List<String> invalidateStatements() {
        return invalidateStatements;
    }
}
