package com.ttegeoji.backend.comment;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 게시물 댓글 조회·저장. 트랜잭션은 호출자가 연다. JPA 엔티티를 두지 않는다(운영 ddl-auto validate, 게이트 결정).
 * 공유 방 판정은 전부 post_rooms.revoked_at IS NULL 이다(004b_privacy).
 */
@Component
@RequiredArgsConstructor
public class CommentQueries {

    /**
     * 판결 확정(JUDGED): 그 방 판결이 FINAL 이고 각하가 아니고 게시물이 삭제되지 않음. p 는 posts 별칭이다.
     *
     * <p>{@code %s} 에는 "댓글이 달린 방" 을 가리키는 식이 들어간다. 판결은 방마다 따로 나므로
     * post_id 만 보면 <b>A 방에서만 확정돼도 B 방 댓글이 열린다</b>. 옛 합산 판결(room_id NULL)은
     * 방 구분이 없어 그대로 인정한다.
     */
    private static final String JUDGED_FOR_ROOM = """
            p.deleted_at IS NULL
            AND EXISTS (SELECT 1 FROM verdicts v
                         WHERE v.post_id = p.id AND v.sentence_status = 'FINAL'
                           AND v.jury_result <> CAST('dismissed' AS verdict)
                           AND (v.room_id = %s OR v.room_id IS NULL))""";

    /** 댓글 행(c)의 방 기준 */
    private static final String JUDGED = JUDGED_FOR_ROOM.formatted("c.room_id");

    public record PostRow(UUID id, UUID authorId, boolean deleted) {
    }

    public record CommentRow(UUID id, UUID postId, UUID userId, boolean deleted) {
    }

    public record CommentView(UUID id, UUID postId, UUID roomId, UUID userId, String nickname, String content,
                              OffsetDateTime createdAt) {
    }

    public record InsertedComment(UUID id, int version) {
    }

    public record RetainCandidate(UUID id, int version) {
    }

    public record SnapshotRow(UUID id, int version, UUID roomId, UUID postId, UUID userId, String content,
                              OffsetDateTime createdAt) {
    }

    private static final RowMapper<CommentView> VIEW = (rs, i) -> new CommentView(
            rs.getObject("id", UUID.class), rs.getObject("post_id", UUID.class), rs.getObject("room_id", UUID.class),
            rs.getObject("user_id", UUID.class), rs.getString("nickname"), rs.getString("content"),
            rs.getObject("created_at", OffsetDateTime.class));

    private final JdbcTemplate jdbc;

    public Optional<PostRow> findPost(UUID postId) {
        return jdbc.query("SELECT id, author_id, deleted_at IS NOT NULL AS deleted FROM posts WHERE id = ?",
                (rs, i) -> new PostRow(rs.getObject("id", UUID.class), rs.getObject("author_id", UUID.class),
                        rs.getBoolean("deleted")),
                postId).stream().findFirst();
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

    /** 그 방 재판이 끝났는가. 끝났으면 새 댓글을 바로 RETAIN 에 넣는다 */
    public boolean isJudged(UUID postId, UUID roomId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM posts p WHERE p.id = ? AND "
                        + JUDGED_FOR_ROOM.formatted("CAST(? AS uuid)") + ")",
                Boolean.class, postId, roomId));
    }

    public InsertedComment insert(UUID postId, UUID roomId, UUID userId, String content) {
        return jdbc.queryForObject("""
                        INSERT INTO post_comments (post_id, room_id, user_id, content) VALUES (?, ?, ?, ?)
                        RETURNING id, version""",
                (rs, i) -> new InsertedComment(rs.getObject("id", UUID.class), rs.getInt("version")),
                postId, roomId, userId, content);
    }

    public Optional<CommentView> findView(UUID commentId) {
        return jdbc.query("""
                        SELECT c.id, c.post_id, c.room_id, c.user_id, pf.nickname, c.content, c.created_at
                          FROM post_comments c JOIN profiles pf ON pf.id = c.user_id
                         WHERE c.id = ?""",
                VIEW, commentId).stream().findFirst();
    }

    /**
     * 요청자가 볼 수 있는 댓글. 철회 안 된 공유 방의 댓글만이고, 작성자가 아니면 자기가 멤버인 방의 댓글만. 삭제 제외, 오래된 순
     */
    /**
     * 한 방의 댓글만. 게시물은 여러 방에 올라가지만 댓글 스레드는 방마다 따로다.
     * 작성자라도 다른 방 댓글은 보지 않는다 — 방 사람들끼리 한 이야기다.
     */
    public List<CommentView> listInRoom(UUID postId, UUID roomId) {
        return jdbc.query("""
                        SELECT c.id, c.post_id, c.room_id, c.user_id, pf.nickname, c.content, c.created_at
                          FROM post_comments c
                          JOIN post_rooms pr ON pr.post_id = c.post_id AND pr.room_id = c.room_id
                                            AND pr.revoked_at IS NULL
                          JOIN profiles pf ON pf.id = c.user_id
                         WHERE c.post_id = ? AND c.room_id = ? AND c.deleted_at IS NULL
                         ORDER BY c.created_at, c.id""",
                VIEW, postId, roomId);
    }

    public List<CommentView> listVisible(UUID postId, UUID userId, boolean postAuthor) {
        return jdbc.query("""
                        SELECT c.id, c.post_id, c.room_id, c.user_id, pf.nickname, c.content, c.created_at
                          FROM post_comments c
                          JOIN post_rooms pr ON pr.post_id = c.post_id AND pr.room_id = c.room_id AND pr.revoked_at IS NULL
                          JOIN profiles pf ON pf.id = c.user_id
                         WHERE c.post_id = ? AND c.deleted_at IS NULL
                           AND (CAST(? AS boolean)
                                OR EXISTS (SELECT 1 FROM room_members rm WHERE rm.room_id = c.room_id AND rm.user_id = ?))
                         ORDER BY c.created_at, c.id""",
                VIEW, postId, postAuthor, userId);
    }

    public Optional<CommentRow> findComment(UUID commentId) {
        return jdbc.query("SELECT id, post_id, user_id, deleted_at IS NOT NULL AS deleted FROM post_comments WHERE id = ?",
                (rs, i) -> new CommentRow(rs.getObject("id", UUID.class), rs.getObject("post_id", UUID.class),
                        rs.getObject("user_id", UUID.class), rs.getBoolean("deleted")),
                commentId).stream().findFirst();
    }

    public void markDeleted(UUID commentId) {
        jdbc.update("UPDATE post_comments SET deleted_at = now() WHERE id = ? AND deleted_at IS NULL", commentId);
    }

    public void markRetained(UUID commentId) {
        jdbc.update("UPDATE post_comments SET retained_at = now() WHERE id = ?", commentId);
    }

    /**
     * RETAIN 을 아직 안 넣은 JUDGED 게시물 댓글을 잠근다. OF c 로 댓글 행만 잠근다 —
     * posts·verdicts 까지 잠그면 scope → verdict 순으로 잡는 finalize·watchdog 와 순서가 엇갈린다(10 §2)
     */
    public List<RetainCandidate> lockRetainCandidates(int limit) {
        return jdbc.query("""
                        SELECT c.id, c.version
                          FROM post_comments c
                         WHERE c.retained_at IS NULL AND c.deleted_at IS NULL
                           AND EXISTS (SELECT 1 FROM posts p WHERE p.id = c.post_id AND %s)
                         ORDER BY c.created_at, c.id
                         LIMIT ?
                           FOR UPDATE OF c SKIP LOCKED""".formatted(JUDGED),
                (rs, i) -> new RetainCandidate(rs.getObject("id", UUID.class), rs.getInt("version")),
                limit);
    }

    /** RETAIN 스냅샷 원본. 댓글 삭제·게시물 삭제·JUDGED 아님·댓글 방 공유 철회면 없다 */
    public Optional<SnapshotRow> findSnapshot(UUID commentId) {
        return jdbc.query("""
                        SELECT c.id, c.version, c.room_id, c.post_id, c.user_id, c.content, c.created_at
                          FROM post_comments c
                          JOIN posts p ON p.id = c.post_id
                          JOIN post_rooms pr ON pr.post_id = c.post_id AND pr.room_id = c.room_id AND pr.revoked_at IS NULL
                         WHERE c.id = ? AND c.deleted_at IS NULL AND %s""".formatted(JUDGED),
                (rs, i) -> new SnapshotRow(rs.getObject("id", UUID.class), rs.getInt("version"),
                        rs.getObject("room_id", UUID.class), rs.getObject("post_id", UUID.class),
                        rs.getObject("user_id", UUID.class), rs.getString("content"),
                        rs.getObject("created_at", OffsetDateTime.class)),
                commentId).stream().findFirst();
    }
}
