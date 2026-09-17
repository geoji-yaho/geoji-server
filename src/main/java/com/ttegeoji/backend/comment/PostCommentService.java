package com.ttegeoji.backend.comment;

import com.ttegeoji.backend.comment.CommentQueries.CommentRow;
import com.ttegeoji.backend.comment.CommentQueries.CommentView;
import com.ttegeoji.backend.comment.CommentQueries.InsertedComment;
import com.ttegeoji.backend.comment.CommentQueries.PostRow;
import com.ttegeoji.backend.comment.dto.CreatePostCommentRequest;
import com.ttegeoji.backend.comment.dto.PostCommentResponse;
import com.ttegeoji.backend.jobs.JobEnqueuer;
import com.ttegeoji.backend.privacy.InvalidationService;
import com.ttegeoji.backend.privacy.ScopeKeys;
import com.ttegeoji.backend.verdictview.PublicApiRejection;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * SPEC 게시물 댓글(1단계·200자·본인 삭제)과 10 §3 RETAIN comment.approved·10 §8 댓글 삭제 무효화.
 * 판정 순서 404 → 403 → 400 은 존재·권한을 입력 오류보다 먼저 숨기려는 것이다(PostVoteService 와 같다).
 * 안전 필터는 AI 워커가 한다. 백엔드는 JUDGED 게시물 댓글을 거르지 않고 전부 넣는다(사용자 9/14).
 */
@Service
@RequiredArgsConstructor
public class PostCommentService {

    static final int CONTENT_MAX = 200;
    // ai 워커 retain_memory 가 기억을 ('COMMENT', comment_id) 로 저장해 invalidate_scope.sql 이 같은 조합으로 찾는다
    static final String SOURCE_TYPE = "COMMENT";

    private final CommentQueries queries;
    private final JobEnqueuer jobEnqueuer;
    private final InvalidationService invalidationService;

    /**
     * 댓글 목록. <b>방을 반드시 지정해야 한다.</b>
     *
     * <p>게시물은 여러 방에 올라가지만 댓글 스레드는 방마다 따로다. 방을 안 주면 어느 방 대화를
     * 달라는 것인지 알 수 없어 거부한다. 예전에는 "작성자는 전부" 로 열어 줬는데, 그 틈으로
     * 작성자 화면에서 두 방 댓글이 한 스레드로 합쳐져 보였다(9/17). 빈 문자열 {@code ?room_id=} 는
     * Spring 이 null 로 바꾸므로 "안 준 것" 과 같게 취급된다 — 그래서 여기서 막는다.
     *
     * @param roomId 보고 있는 방. null 이면 400
     */
    @Transactional(readOnly = true)
    public List<PostCommentResponse> list(UUID postId, UUID userId, UUID roomId) {
        visiblePost(postId, userId);
        if (roomId == null) {
            throw new PublicApiRejection(HttpStatus.BAD_REQUEST, "어느 방의 댓글인지 room_id 를 지정해 주세요.");
        }
        return inRoom(postId, userId, roomId).stream().map(PostCommentService::toResponse).toList();
    }

    /** 그 방에서 볼 자격이 있어야 준다. 멤버가 아니면 게시물 자체를 못 본 것과 같게 404 */
    private List<CommentQueries.CommentView> inRoom(UUID postId, UUID userId, UUID roomId) {
        if (!queries.isMemberOfActiveSharedRoom(postId, roomId, userId)) {
            throw PublicApiRejection.notFound();
        }
        return queries.listInRoom(postId, roomId);
    }

    @Transactional
    public PostCommentResponse create(UUID postId, UUID userId, CreatePostCommentRequest request) {
        visiblePost(postId, userId);
        UUID roomId = parseRoomId(request.roomId());
        if (!queries.isMemberOfActiveSharedRoom(postId, roomId, userId)) {
            throw new PublicApiRejection(HttpStatus.FORBIDDEN, "이 방에서는 댓글을 달 수 없습니다.");
        }
        String content = request.content();
        if (content == null || content.isBlank()) {
            throw new PublicApiRejection(HttpStatus.BAD_REQUEST, "댓글 내용을 입력해 주세요.");
        }
        // DB CHECK 는 char_length(코드 포인트) 기준이다
        if (content.codePointCount(0, content.length()) > CONTENT_MAX) {
            throw new PublicApiRejection(HttpStatus.BAD_REQUEST, "댓글은 200자 이하여야 합니다.");
        }

        InsertedComment inserted = queries.insert(postId, roomId, userId, content);
        if (queries.isJudged(postId, roomId)) {
            // 확정 전 댓글은 retained_at NULL 로 남기고 CommentRetainScheduler 가 확정 뒤 넣는다
            jobEnqueuer.enqueueRetainComment(inserted.id().toString(), inserted.version());
            queries.markRetained(inserted.id());
        }
        return toResponse(queries.findView(inserted.id()).orElseThrow());
    }

    /**
     * 본인 댓글 삭제. scope epoch 를 먼저 잠그고 올린 뒤(InvalidationService) 같은 트랜잭션에서 원본을 비활성화한다(10 §8).
     * 본인은 게시물을 볼 수 없게 됐어도(방 공유 철회) 지울 수 있다. 진행 중 RETAIN job 은 끄지 않는다 — snapshot 404 로 워커가 버린다.
     */
    @Transactional
    public void delete(UUID postId, UUID commentId, UUID userId) {
        PostRow post = queries.findPost(postId).filter(p -> !p.deleted()).orElseThrow(PublicApiRejection::notFound);
        CommentRow comment = queries.findComment(commentId)
                .filter(c -> c.postId().equals(postId))
                .orElseThrow(PostCommentService::commentNotFound);
        if (!comment.userId().equals(userId)) {
            if (!canView(post, userId)) {
                throw PublicApiRejection.notFound();
            }
            throw new PublicApiRejection(HttpStatus.FORBIDDEN, "본인 댓글만 삭제할 수 있습니다.");
        }
        // 이미 삭제됐으면 epoch·기록을 더 올리지 않고 성공(게시물 삭제와 같은 규칙)
        if (comment.deleted()) {
            return;
        }
        invalidationService.invalidate(List.of(ScopeKeys.post(postId)), SOURCE_TYPE, commentId.toString());
        queries.markDeleted(commentId);
    }

    private PostRow visiblePost(UUID postId, UUID userId) {
        return queries.findPost(postId)
                .filter(p -> !p.deleted() && canView(p, userId))
                .orElseThrow(PublicApiRejection::notFound);
    }

    private boolean canView(PostRow post, UUID userId) {
        return post.authorId().equals(userId) || queries.isMemberOfAnyActiveSharedRoom(post.id(), userId);
    }

    private static UUID parseRoomId(String roomId) {
        if (roomId != null) {
            try {
                return UUID.fromString(roomId);
            } catch (IllegalArgumentException ignored) {
                // 아래 400
            }
        }
        throw new PublicApiRejection(HttpStatus.BAD_REQUEST, "댓글을 달 방을 지정해 주세요.");
    }

    private static PublicApiRejection commentNotFound() {
        return new PublicApiRejection(HttpStatus.NOT_FOUND, "댓글을 찾을 수 없습니다.");
    }

    private static PostCommentResponse toResponse(CommentView view) {
        return new PostCommentResponse(view.id().toString(), view.postId().toString(), view.roomId().toString(),
                view.userId().toString(), view.nickname(), view.content(), view.createdAt());
    }
}
