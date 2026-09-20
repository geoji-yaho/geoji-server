package com.ttegeoji.backend.seed;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * 19 §6 ai-member 가 쓰는 조회·INSERT. SeedQueries 는 seed 프로필에만 있어 같은 문장을 여기 둔다
 * (code-layout 룰: 자기 패키지 *Queries). backend role 에 DELETE 가 없어 멱등은 "있으면 건너뜀"이다.
 */
@Component
public class AiMemberQueries {

    private final JdbcTemplate jdbc;

    AiMemberQueries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return 새로 넣었으면 true. 이미 멤버면 false */
    boolean insertMemberIfAbsent(UUID roomId, UUID userId) {
        return jdbc.update("INSERT INTO room_members (room_id, user_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                roomId, userId) == 1;
    }

    /** 같은 작성자·방·내용의 삭제되지 않은 게시물(SeedQueries.findPosts 와 같은 멱등 기준, 19 §7). 생성 순. */
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
}
