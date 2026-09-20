package com.ttegeoji.backend.verdict;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * finalize(10 §5)·짤 선택(10 §11)이 쓰는 조회·갱신. 앞 작업 repository 를 고치지 않으려고 따로 둔다(code-layout 룰).
 * 트랜잭션은 FinalizeService 가 연다. job 행 잠금은 privacy scope·verdict 를 잠근 뒤에만 부른다(10 §2 잠금 순서).
 */
@Component
@RequiredArgsConstructor
public class FinalizeQueries {

    /** @param roomId 방별 판결의 방. 옛 합산 판결은 null */
    public record VerdictHeader(UUID verdictId, UUID postId, UUID authorId, UUID roomId) {
    }

    public record PostState(boolean deleted) {
    }

    /** leaseValid·deadlinePassed 는 DB now() 기준 */
    public record LockedJob(UUID id, String kind, String status, UUID generationId, boolean leaseValid,
                            boolean deadlinePassed) {
    }

    /** labelMapJson 은 {"F0": "<evidence uuid>", ...} */
    public record Dossier(UUID id, String postId, String labelMapJson, boolean invalidated) {
    }

    public record EvidenceState(UUID id, boolean invalidated) {
    }

    private final JdbcTemplate jdbcTemplate;

    /** 잠그기 전에 scope key 를 만들려고 읽는다. 잠금 뒤 값은 다시 확인한다 */
    public Optional<VerdictHeader> findVerdictHeader(UUID verdictId) {
        return jdbcTemplate.query("""
                        SELECT v.id, v.post_id, v.room_id, p.author_id
                          FROM verdicts v JOIN posts p ON p.id = v.post_id
                         WHERE v.id = ?""",
                (rs, i) -> new VerdictHeader(rs.getObject("id", UUID.class), rs.getObject("post_id", UUID.class),
                        rs.getObject("author_id", UUID.class), rs.getObject("room_id", UUID.class)), verdictId)
                .stream().findFirst();
    }

    public PostState findPostState(UUID postId) {
        return jdbcTemplate.queryForObject("SELECT deleted_at IS NOT NULL AS deleted FROM posts WHERE id = ?",
                (rs, i) -> new PostState(rs.getBoolean("deleted")), postId);
    }

    /** 공유 방. 순서를 고정해 잠금 전·후 목록을 그대로 비교한다 */
    public List<UUID> findRoomIds(UUID postId) {
        return jdbcTemplate.query("SELECT room_id FROM post_rooms WHERE post_id = ? ORDER BY room_id",
                (rs, i) -> rs.getObject("room_id", UUID.class), postId);
    }

    /** 10 §5 2단계 마지막 잠금. 없으면 빈 결과 */
    public Optional<LockedJob> lockJob(UUID jobId) {
        return jdbcTemplate.query("""
                        SELECT id, kind, status, generation_id,
                               COALESCE(lease_until > now(), false) AS lease_valid,
                               COALESCE(now() >= deadline_at, false) AS deadline_passed
                          FROM ai.jobs WHERE id = ? FOR UPDATE""",
                (rs, i) -> new LockedJob(rs.getObject("id", UUID.class), rs.getString("kind"), rs.getString("status"),
                        rs.getObject("generation_id", UUID.class), rs.getBoolean("lease_valid"),
                        rs.getBoolean("deadline_passed")), jobId)
                .stream().findFirst();
    }

    /** 10 §5 6단계 최초 SENTENCE: DB now() < verdicts.deadline_at 이 아니면 true. deadline_at 이 비면 false(가짜 백엔드와 같다) */
    public boolean verdictDeadlinePassed(UUID verdictId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT COALESCE(now() >= deadline_at, false) FROM verdicts WHERE id = ?", Boolean.class, verdictId));
    }

    /** DB now() + minutes. JPA 가 verdict 행 전체를 덮어쓰므로 SQL 로 갱신하지 않고 값을 받아 엔티티에 넣는다 */
    public OffsetDateTime dbNowPlusMinutes(int minutes) {
        return jdbcTemplate.queryForObject("SELECT now() + make_interval(mins => ?)", OffsetDateTime.class, minutes);
    }

    public Optional<Dossier> findDossier(UUID dossierId) {
        return jdbcTemplate.query("""
                        SELECT id, post_id, label_map::text AS label_map, invalidated_at IS NOT NULL AS invalidated
                          FROM ai.dossiers WHERE id = ?""",
                (rs, i) -> new Dossier(rs.getObject("id", UUID.class), rs.getString("post_id"),
                        rs.getString("label_map"), rs.getBoolean("invalidated")), dossierId)
                .stream().findFirst();
    }

    /** 그 dossier 에 속한 evidence 만. 없는 id 는 결과에 빠진다 */
    public Map<UUID, EvidenceState> findEvidence(UUID dossierId, Collection<UUID> evidenceIds) {
        Map<UUID, EvidenceState> found = new HashMap<>();
        if (evidenceIds.isEmpty()) {
            return found;
        }
        jdbcTemplate.query(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    SELECT id, invalidated_at IS NOT NULL AS invalidated
                      FROM ai.evidence WHERE dossier_id = ? AND id = ANY(?)""");
            ps.setObject(1, dossierId);
            ps.setArray(2, connection.createArrayOf("uuid", evidenceIds.toArray()));
            return ps;
        }, rs -> {
            UUID id = rs.getObject("id", UUID.class);
            found.put(id, new EvidenceState(id, rs.getBoolean("invalidated")));
        });
        return found;
    }

    /** 10 §5 12단계. lease 를 비워 reaper 대상에서 뺀다. generation_id 는 추적용으로 남긴다 */
    public boolean markJobSucceeded(UUID jobId) {
        return jdbcTemplate.update("""
                UPDATE ai.jobs SET status = 'SUCCEEDED', lease_until = NULL, updated_at = now()
                 WHERE id = ? AND status = 'RUNNING'""", jobId) == 1;
    }

    /**
     * 10 §11 −5 대상: 같은 사용자(게시물 작성자)의 다른 판결에 고정된 짤 중 최근 limit 장. 최근은 평결 확정 시각 순.
     */
    public List<String> findRecentMemeImageIds(UUID authorId, UUID excludeVerdictId, int limit) {
        return jdbcTemplate.query("""
                        SELECT v.meme_image_id
                          FROM verdicts v JOIN posts p ON p.id = v.post_id
                         WHERE p.author_id = ? AND v.id <> ? AND v.meme_image_id IS NOT NULL
                         ORDER BY v.confirmed_at DESC, v.id
                         LIMIT ?""",
                (rs, i) -> rs.getObject("meme_image_id", UUID.class).toString(), authorId, excludeVerdictId, limit);
    }
}
