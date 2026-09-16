package com.ttegeoji.backend.verdictview;

import com.ttegeoji.backend.domain.enums.SpiceLevel;
import com.ttegeoji.backend.domain.enums.VerdictType;
import com.ttegeoji.backend.verdictview.dto.PostDetailResponse;
import com.ttegeoji.backend.verdictview.dto.RoomPostSummaryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 게시물 읽기 조회(S-06 피드·S-14 투표·S-10 판결의 사건 개요). 판결문 쪽은 VerdictViewQueries 가 본다.
 * 삭제된 게시물과 철회된 공유 방은 어디서도 나오지 않는다.
 */
@Component
@RequiredArgsConstructor
public class PostReadQueries {

    /** 반대는 유죄·기각, 찬성은 무죄·동의다(VERDICT_SIDES 와 같은 편 가르기) */
    private static final String OPPOSE_FILTER = "verdict IN ('guilty', 'disagree')";
    private static final String SUPPORT_FILTER = "verdict IN ('notGuilty', 'agree')";

    private final JdbcTemplate jdbc;

    public record PostRow(UUID id, String postType, int amountKrw, String category, String item, String reason,
                          UUID authorId, String authorNickname, OffsetDateTime voteDeadlineAt,
                          OffsetDateTime createdAt, VerdictType juryStatus) {
    }

    /**
     * 삭제되지 않은 게시물 한 건. 평결이 아직 없으면 juryStatus 는 null.
     *
     * <p>판결은 방마다 따로 생기므로 room_id 로 하나만 고른다. 조건 없이 조인하면 방 수만큼 행이 곱해진다.
     * 그 방 판결이 없으면 옛 합산 판결(room_id IS NULL)로 떨어진다.
     */
    public Optional<PostRow> findPost(UUID postId, UUID roomId) {
        return jdbc.query("""
                        SELECT p.id, p.post_type::text AS post_type, p.amount_krw, p.category, p.item, p.reason,
                               p.author_id, pr.nickname AS author_nickname, p.vote_deadline_at, p.created_at,
                               v.jury_result::text AS jury_result
                          FROM posts p
                          JOIN profiles pr ON pr.id = p.author_id
                          LEFT JOIN LATERAL (
                              SELECT vv.jury_result FROM verdicts vv
                               WHERE vv.post_id = p.id AND (vv.room_id = ? OR vv.room_id IS NULL)
                               ORDER BY vv.room_id NULLS LAST LIMIT 1) v ON true
                         WHERE p.id = ? AND p.deleted_at IS NULL""",
                (rs, i) -> new PostRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("post_type"),
                        rs.getInt("amount_krw"),
                        rs.getString("category"),
                        rs.getString("item"),
                        rs.getString("reason"),
                        rs.getObject("author_id", UUID.class),
                        rs.getString("author_nickname"),
                        rs.getObject("vote_deadline_at", OffsetDateTime.class),
                        rs.getObject("created_at", OffsetDateTime.class),
                        verdictOf(rs.getString("jury_result"))),
                roomId, postId).stream().findFirst();
    }

    /** 철회되지 않은 공유 방 */
    public List<PostDetailResponse.RoomBrief> sharedRooms(UUID postId) {
        return jdbc.query("""
                        SELECT r.id, r.name, r.spice_level::text AS spice_level
                          FROM post_rooms pr
                          JOIN rooms r ON r.id = pr.room_id
                         WHERE pr.post_id = ? AND pr.revoked_at IS NULL AND r.deleted_at IS NULL
                         ORDER BY r.created_at""",
                (rs, i) -> new PostDetailResponse.RoomBrief(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        SpiceLevel.valueOf(rs.getString("spice_level"))),
                postId);
    }

    /**
     * 볼 수 있는 표만. 게시물은 작성자가 속한 방 전부에 올라가므로 표도 여러 방에서 들어온다.
     * 댓글과 같은 규칙으로 가린다(10 §9): 작성자는 전부 보고, 그 밖에는 자기가 멤버인 방에서
     * 나온 표만 본다. 안 그러면 같은 방이 아닌 사람의 닉네임과 투표 사유가 보인다.
     *
     * <p>집계(tally)는 가리지 않는다. 평결을 만든 수 자체라 방마다 다르면 "2인 중 2인이 유죄"
     * 같은 문구와 어긋난다. 숫자는 누가 어느 쪽인지 드러내지 않는다.
     */
    public List<PostDetailResponse.VoteBrief> visibleVotes(UUID postId, UUID viewerId, boolean isAuthor,
                                                          UUID roomId) {
        return jdbc.query("""
                        SELECT v.id, v.voter_id, pr.nickname AS voter_nickname, v.verdict::text AS verdict,
                               v.reason, v.created_at
                          FROM votes v
                          JOIN profiles pr ON pr.id = v.voter_id
                         WHERE v.post_id = ?
                           AND (CAST(? AS uuid) IS NULL OR v.room_id = ?)
                           AND (CAST(? AS boolean)
                                OR EXISTS (SELECT 1 FROM room_members rm
                                            WHERE rm.room_id = v.room_id AND rm.user_id = ?))
                         ORDER BY v.created_at""",
                (rs, i) -> new PostDetailResponse.VoteBrief(
                        rs.getObject("id", UUID.class),
                        rs.getObject("voter_id", UUID.class),
                        rs.getString("voter_nickname"),
                        VerdictType.valueOf(rs.getString("verdict")),
                        rs.getString("reason"),
                        rs.getObject("created_at", OffsetDateTime.class)),
                postId, roomId, roomId, isAuthor, viewerId);
    }

    /** 그 방 집계. 방마다 따로 재판하므로 그 방 표만 센다 */
    public PostDetailResponse.Tally tally(UUID postId, UUID roomId) {
        return jdbc.queryForObject("""
                SELECT count(*) FILTER (WHERE verdict IN ('guilty', 'disagree')) AS oppose,
                       count(*) FILTER (WHERE verdict IN ('notGuilty', 'agree')) AS support
                  FROM votes WHERE post_id = ? AND room_id = ?""",
                (rs, i) -> new PostDetailResponse.Tally(rs.getInt("oppose"), rs.getInt("support")),
                postId, roomId);
    }

    /** 투표 가능 인원: **그 방** 멤버에서 작성자를 뺀 수(방별 정족수와 같은 모집단) */
    public int eligibleVoterCount(UUID postId, UUID roomId, UUID authorId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*)
                  FROM post_rooms pr
                  JOIN room_members rm ON rm.room_id = pr.room_id
                 WHERE pr.post_id = ? AND pr.room_id = ? AND pr.revoked_at IS NULL AND rm.user_id <> ?""",
                Integer.class, postId, roomId, authorId);
        return count == null ? 0 : count;
    }

    /** 방 피드. 철회되지 않은 공유만 보이고, 최신 글이 위다 */
    public List<RoomPostSummaryResponse> roomFeed(UUID roomId, UUID viewerId, int limit) {
        return jdbc.query("""
                        SELECT p.id, p.post_type::text AS post_type, p.amount_krw, p.category, p.item,
                               p.author_id, pr.nickname AS author_nickname, p.vote_deadline_at, p.created_at,
                               v.jury_result::text AS jury_result,
                               (SELECT count(*) FROM votes t
                                 WHERE t.post_id = p.id AND t.room_id = rp.room_id AND t.%s) AS oppose,
                               (SELECT count(*) FROM votes t
                                 WHERE t.post_id = p.id AND t.room_id = rp.room_id AND t.%s) AS support,
                               EXISTS (SELECT 1 FROM votes t
                                        WHERE t.post_id = p.id AND t.room_id = rp.room_id AND t.voter_id = ?) AS voted
                          FROM posts p
                          JOIN post_rooms rp ON rp.post_id = p.id AND rp.room_id = ? AND rp.revoked_at IS NULL
                          JOIN profiles pr ON pr.id = p.author_id
                          LEFT JOIN LATERAL (
                              SELECT vv.jury_result FROM verdicts vv
                               WHERE vv.post_id = p.id AND (vv.room_id = rp.room_id OR vv.room_id IS NULL)
                               ORDER BY vv.room_id NULLS LAST LIMIT 1) v ON true
                         WHERE p.deleted_at IS NULL
                         ORDER BY p.created_at DESC
                         LIMIT ?""".formatted(OPPOSE_FILTER, SUPPORT_FILTER),
                (rs, i) -> new RoomPostSummaryResponse(
                        rs.getObject("id", UUID.class),
                        rs.getString("post_type"),
                        rs.getInt("amount_krw"),
                        rs.getString("category"),
                        rs.getString("item"),
                        rs.getObject("author_id", UUID.class),
                        rs.getString("author_nickname"),
                        rs.getObject("vote_deadline_at", OffsetDateTime.class),
                        rs.getObject("created_at", OffsetDateTime.class),
                        verdictOf(rs.getString("jury_result")),
                        new PostDetailResponse.Tally(rs.getInt("oppose"), rs.getInt("support")),
                        rs.getBoolean("voted")),
                viewerId, roomId, limit);
    }

    /** viewerId 가 이 방의 멤버인가. 삭제된 방은 아니다 */
    public boolean isRoomMember(UUID roomId, UUID viewerId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM room_members rm
                                 JOIN rooms r ON r.id = rm.room_id
                                WHERE rm.room_id = ? AND rm.user_id = ? AND r.deleted_at IS NULL)""",
                Boolean.class, roomId, viewerId));
    }

    /** 평결 확정 전에는 사유를 비운다. 확정 전에 남의 사유가 보이면 표가 쏠린다 */
    public static List<PostDetailResponse.VoteBrief> hideReasonsUntilConfirmed(
            List<PostDetailResponse.VoteBrief> votes, boolean confirmed) {
        if (confirmed) {
            return votes;
        }
        List<PostDetailResponse.VoteBrief> hidden = new ArrayList<>(votes.size());
        for (PostDetailResponse.VoteBrief vote : votes) {
            hidden.add(new PostDetailResponse.VoteBrief(vote.id(), vote.voterId(), vote.voterNickname(),
                    vote.verdict(), null, vote.createdAt()));
        }
        return hidden;
    }

    private static VerdictType verdictOf(String value) {
        return value == null ? null : VerdictType.valueOf(value);
    }
}
