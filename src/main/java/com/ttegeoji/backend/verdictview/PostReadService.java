package com.ttegeoji.backend.verdictview;

import com.ttegeoji.backend.verdictview.dto.PostDetailResponse;
import com.ttegeoji.backend.verdictview.dto.RoomPostSummaryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 게시물 읽기(10 §9 보완). 볼 수 있는 사람은 작성자 또는 철회되지 않은 공유 방의 멤버다.
 * 없는 글·삭제된 글·볼 수 없는 사람을 구분하지 않고 모두 404 로 답한다(판결 조회와 같은 규칙).
 */
@Service
@RequiredArgsConstructor
public class PostReadService {

    static final int FEED_LIMIT = 100;

    private final PostReadQueries queries;
    private final VerdictViewQueries verdictViewQueries;

    /**
     * @param roomId 보고 있는 방. 판결·집계·투표 가능 인원이 모두 이 방 기준이다.
     *               게시물은 여러 방에 올라가지만 재판은 방마다 따로 한다
     */
    @Transactional(readOnly = true)
    public PostDetailResponse detail(UUID postId, UUID viewerId, UUID roomId) {
        PostReadQueries.PostRow post = queries.findPost(postId, roomId).orElseThrow(PublicApiRejection::notFound);
        boolean isAuthor = post.authorId().equals(viewerId);
        if (!isAuthor && !verdictViewQueries.isMemberOfAnyActiveSharedRoom(postId, viewerId)) {
            throw PublicApiRejection.notFound();
        }
        if (roomId != null && !isAuthor && !verdictViewQueries.isMemberOfActiveSharedRoom(postId, roomId, viewerId)) {
            throw PublicApiRejection.notFound();
        }

        boolean confirmed = post.juryStatus() != null;
        List<PostDetailResponse.VoteBrief> visible = queries.visibleVotes(postId, viewerId, isAuthor, roomId);
        // 내 표의 사유는 내가 쓴 것이라 확정 전에도 가리지 않는다
        PostDetailResponse.VoteBrief mine = visible.stream()
                .filter(vote -> vote.voterId().equals(viewerId))
                .findFirst()
                .orElse(null);

        return new PostDetailResponse(
                post.id(), post.postType(), post.amountKrw(), post.category(), post.item(), post.reason(),
                post.authorId(), post.authorNickname(), post.voteDeadlineAt(), post.createdAt(),
                queries.sharedRooms(postId),
                post.juryStatus(),
                roomId == null ? new PostDetailResponse.Tally(0, 0) : queries.tally(postId, roomId),
                PostReadQueries.hideReasonsUntilConfirmed(visible, confirmed),
                mine,
                !isAuthor && mine == null && !confirmed,
                roomId == null ? 0 : queries.eligibleVoterCount(postId, roomId, post.authorId()));
    }

    @Transactional(readOnly = true)
    public List<RoomPostSummaryResponse> roomFeed(UUID roomId, UUID viewerId) {
        if (!queries.isRoomMember(roomId, viewerId)) {
            throw new PublicApiRejection(HttpStatus.NOT_FOUND, "방을 찾을 수 없습니다.");
        }
        return queries.roomFeed(roomId, viewerId, FEED_LIMIT);
    }
}
