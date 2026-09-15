package com.ttegeoji.backend.seed;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 시드가 쓰는 조회와 공개 API 가 없는 행(profiles·rooms·room_members) INSERT. 새 엔티티·repository 를 만들지 않는다.
 * backend role 에 DELETE 가 없어 재실행 멱등은 "있으면 건너뜀"이다.
 */
@Component
@Profile("seed")
class SeedQueries {

    private final JdbcTemplate jdbc;

    SeedQueries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void insertProfileIfAbsent(UUID id, String nickname, int monthlyBudget) {
        jdbc.update("INSERT INTO profiles (id, nickname, monthly_budget) VALUES (?, ?, ?) ON CONFLICT (id) DO NOTHING",
                id, nickname, monthlyBudget);
    }

    Optional<UUID> findRoom(UUID createdBy, String name) {
        return jdbc.queryForList("SELECT id FROM rooms WHERE created_by = ? AND name = ? ORDER BY created_at, id",
                UUID.class, createdBy, name).stream().findFirst();
    }

    UUID insertRoom(UUID createdBy, String name, String spiceLevel, int voteDeadlineMinutes, List<String> rules) {
        return jdbc.query(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO rooms (name, spice_level, vote_deadline_minutes, rules, created_by)
                    VALUES (?, CAST(? AS spice_level), ?, ?, ?) RETURNING id""");
            ps.setString(1, name);
            ps.setString(2, spiceLevel);
            ps.setInt(3, voteDeadlineMinutes);
            ps.setArray(4, con.createArrayOf("text", rules.toArray()));
            ps.setObject(5, createdBy);
            return ps;
        }, (rs, i) -> rs.getObject("id", UUID.class)).getFirst();
    }

    void insertMemberIfAbsent(UUID roomId, UUID userId) {
        jdbc.update("INSERT INTO room_members (room_id, user_id) VALUES (?, ?) ON CONFLICT DO NOTHING", roomId, userId);
    }

    /** 같은 작성자·방·내용의 삭제되지 않은 게시물. 스타벅스 두 건처럼 내용이 같으면 생성 순서로 구분한다. */
    List<UUID> findPosts(UUID authorId, UUID roomId, String postType, int amountKrw, String category, String item,
                         String reason) {
        return jdbc.queryForList("""
                        SELECT p.id FROM posts p JOIN post_rooms pr ON pr.post_id = p.id AND pr.room_id = ?
                         WHERE p.author_id = ? AND p.post_type = CAST(? AS post_type) AND p.amount_krw = ?
                           AND p.category = ? AND p.item = ? AND p.reason IS NOT DISTINCT FROM ?
                           AND p.deleted_at IS NULL
                         ORDER BY p.created_at, p.id""",
                UUID.class, roomId, authorId, postType, amountKrw, category, item, reason);
    }

    boolean voteExists(UUID postId, UUID voterId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM votes WHERE post_id = ? AND voter_id = ?)", Boolean.class, postId, voterId));
    }

    Optional<UUID> verdictId(UUID postId) {
        return jdbc.queryForList("SELECT id FROM verdicts WHERE post_id = ?", UUID.class, postId).stream().findFirst();
    }

    Optional<String> juryResult(UUID postId) {
        return jdbc.queryForList("SELECT jury_result::text FROM verdicts WHERE post_id = ?", String.class, postId)
                .stream().findFirst();
    }

    boolean commentExists(UUID postId, UUID userId, String content) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                        SELECT EXISTS (SELECT 1 FROM post_comments
                                        WHERE post_id = ? AND user_id = ? AND content = ? AND deleted_at IS NULL)""",
                Boolean.class, postId, userId, content));
    }

    OffsetDateTime postCreatedAt(UUID postId) {
        return jdbc.queryForObject("SELECT created_at FROM posts WHERE id = ?", OffsetDateTime.class, postId);
    }
}
