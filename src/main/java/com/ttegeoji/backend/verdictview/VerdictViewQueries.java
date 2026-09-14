package com.ttegeoji.backend.verdictview;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 투표·판결 조회·공유 카드가 쓰는 조회. 트랜잭션은 호출자가 연다.
 * 공유 방 판정은 전부 post_rooms.revoked_at IS NULL 이다. PostRoom 엔티티는 철회를 모른다(004b_privacy).
 */
@Component
@RequiredArgsConstructor
public class VerdictViewQueries {

    public record PostRow(UUID id, UUID authorId, String postType, boolean deleted) {
    }

    /** 투표용으로 잠근 게시물. deadlinePassed = vote_deadline_at ≤ DB now() */
    public record LockedPostForVote(UUID id, UUID authorId, String postType, boolean deleted, boolean deadlinePassed) {
    }

    public record InsertedVote(UUID id, OffsetDateTime createdAt) {
    }

    public record VerdictRow(UUID id, String juryResult, String sentenceStatus, String sentence,
                             String sentencingReason, String textStatus, long textVersion, String appliedIntensity,
                             String defaultIntensity, String memeTag, UUID memeImageId, String memeImageUrl) {

        /** applied_intensity 가 비면 default_intensity */
        public String appliedOrDefaultIntensity() {
            return appliedIntensity != null ? appliedIntensity : defaultIntensity;
        }
    }

    public record TextRow(String headline, String statementJson, String source, long textVersion,
                          String privacyEpochSnapshotJson) {
    }

    /** 인용 한 건. publicUsable = evidence 가 있고 scope.visibility = PUBLIC 이고 무효화되지 않음 */
    public record EvidenceRef(String fieldPath, boolean publicUsable) {
    }

    private final JdbcTemplate jdbc;

    public Optional<PostRow> findPost(UUID postId) {
        return jdbc.query("""
                        SELECT id, author_id, post_type::text AS post_type, deleted_at IS NOT NULL AS deleted
                          FROM posts WHERE id = ?""",
                (rs, i) -> new PostRow(rs.getObject("id", UUID.class), rs.getObject("author_id", UUID.class),
                        rs.getString("post_type"), rs.getBoolean("deleted")),
                postId).stream().findFirst();
    }

    /**
     * 같은 게시물 투표를 직렬화한다. JuryQueries.lockPost 와 같은 FOR NO KEY UPDATE 라
     * 뒤이은 onVoteCast 의 잠금·표 INSERT 의 FK KEY SHARE 와 부딪히지 않는다.
     */
    public Optional<LockedPostForVote> lockPostForVote(UUID postId) {
        return jdbc.query("""
                        SELECT id, author_id, post_type::text AS post_type, deleted_at IS NOT NULL AS deleted,
                               vote_deadline_at <= now() AS deadline_passed
                          FROM posts WHERE id = ? FOR NO KEY UPDATE""",
                (rs, i) -> new LockedPostForVote(rs.getObject("id", UUID.class), rs.getObject("author_id", UUID.class),
                        rs.getString("post_type"), rs.getBoolean("deleted"), rs.getBoolean("deadline_passed")),
                postId).stream().findFirst();
    }

    /** roomId 가 이 게시물의 철회 안 된 공유 방인가 */
    public boolean isActiveSharedRoom(UUID postId, UUID roomId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM post_rooms
                                WHERE post_id = ? AND room_id = ? AND revoked_at IS NULL)""",
                Boolean.class, postId, roomId));
    }

    /** roomId 가 철회 안 된 공유 방이고 userId 가 그 방 멤버인가 */
    public boolean isMemberOfActiveSharedRoom(UUID postId, UUID roomId, UUID userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM post_rooms pr
                                 JOIN room_members rm ON rm.room_id = pr.room_id
                                WHERE pr.post_id = ? AND pr.room_id = ? AND pr.revoked_at IS NULL
                                  AND rm.user_id = ?)""",
                Boolean.class, postId, roomId, userId));
    }

    /** userId 가 철회 안 된 공유 방 어느 하나의 멤버인가 */
    public boolean isMemberOfAnyActiveSharedRoom(UUID postId, UUID userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM post_rooms pr
                                 JOIN room_members rm ON rm.room_id = pr.room_id
                                WHERE pr.post_id = ? AND pr.revoked_at IS NULL AND rm.user_id = ?)""",
                Boolean.class, postId, userId));
    }

    public Optional<String> roomSpiceLevel(UUID roomId) {
        return jdbc.query("SELECT spice_level::text AS spice_level FROM rooms WHERE id = ?",
                (rs, i) -> rs.getString("spice_level"), roomId).stream().findFirst();
    }

    public boolean verdictExists(UUID postId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM verdicts WHERE post_id = ?)", Boolean.class, postId));
    }

    public boolean voteExists(UUID postId, UUID voterId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM votes WHERE post_id = ? AND voter_id = ?)", Boolean.class, postId, voterId));
    }

    public InsertedVote insertVote(UUID postId, UUID voterId, UUID roomId, String verdict, String reason) {
        return jdbc.queryForObject("""
                        INSERT INTO votes (post_id, voter_id, room_id, verdict, reason)
                        VALUES (?, ?, ?, CAST(? AS verdict), ?)
                        RETURNING id, created_at""",
                (rs, i) -> new InsertedVote(rs.getObject("id", UUID.class),
                        rs.getObject("created_at", OffsetDateTime.class)),
                postId, voterId, roomId, verdict, reason);
    }

    public Optional<VerdictRow> findVerdict(UUID postId) {
        return jdbc.query("""
                        SELECT v.id, v.jury_result::text AS jury_result, v.sentence_status, v.sentence::text AS sentence,
                               v.sentencing_reason, v.text_status, v.text_version,
                               v.applied_intensity::text AS applied_intensity, v.default_intensity::text AS default_intensity,
                               m.tag AS meme_tag, m.id AS meme_image_id, m.image_url AS meme_image_url
                          FROM verdicts v
                          LEFT JOIN meme_images m ON m.id = v.meme_image_id
                         WHERE v.post_id = ?""",
                (rs, i) -> new VerdictRow(rs.getObject("id", UUID.class), rs.getString("jury_result"),
                        rs.getString("sentence_status"), rs.getString("sentence"), rs.getString("sentencing_reason"),
                        rs.getString("text_status"), rs.getLong("text_version"), rs.getString("applied_intensity"),
                        rs.getString("default_intensity"), rs.getString("meme_tag"),
                        rs.getObject("meme_image_id", UUID.class), rs.getString("meme_image_url")),
                postId).stream().findFirst();
    }

    public Optional<TextRow> findText(UUID verdictId, String intensity) {
        return jdbc.query("""
                        SELECT headline, statement::text AS statement, source, text_version,
                               privacy_epoch_snapshot::text AS privacy_epoch_snapshot
                          FROM verdict_texts
                         WHERE verdict_id = ? AND intensity = CAST(? AS spice_level)""",
                (rs, i) -> new TextRow(rs.getString("headline"), rs.getString("statement"), rs.getString("source"),
                        rs.getLong("text_version"), rs.getString("privacy_epoch_snapshot")),
                verdictId, intensity).stream().findFirst();
    }

    /** 그 강도·text_version 문구가 인용한 근거. 공개 판정은 AI domain/visibility.py usable_for_share_card 와 같다 */
    public List<EvidenceRef> evidenceRefs(UUID verdictId, String intensity, long textVersion) {
        return jdbc.query("""
                        SELECT r.field_path,
                               (e.id IS NOT NULL AND e.scope ->> 'visibility' = 'PUBLIC' AND e.invalidated_at IS NULL)
                                   AS public_usable
                          FROM ai.text_evidence_refs r
                          LEFT JOIN ai.evidence e ON e.id = r.evidence_id
                         WHERE r.verdict_id = ? AND r.intensity = ? AND r.text_version = ?""",
                (rs, i) -> new EvidenceRef(rs.getString("field_path"), rs.getBoolean("public_usable")),
                verdictId, intensity, textVersion);
    }
}
