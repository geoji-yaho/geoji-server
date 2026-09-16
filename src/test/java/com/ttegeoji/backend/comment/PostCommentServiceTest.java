package com.ttegeoji.backend.comment;

import com.ttegeoji.backend.comment.dto.CreatePostCommentRequest;
import com.ttegeoji.backend.comment.dto.PostCommentResponse;
import com.ttegeoji.backend.jobs.EnqueuedJob;
import com.ttegeoji.backend.jobs.JobEnqueuer;
import com.ttegeoji.backend.privacy.ScopeKeys;
import com.ttegeoji.backend.support.PostgresContainerSupport;
import com.ttegeoji.backend.verdictview.PublicApiRejection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 실제로 커밋한다. 테스트마다 새 사용자·방·게시물 UUID 라 scope key·댓글이 섞이지 않는다.
// 다른 테스트 컨텍스트의 InvalidationScheduler 가 PENDING 기록을 먼저 처리할 수 있어 기록 status 는 보지 않는다
@SpringBootTest
class PostCommentServiceTest extends PostgresContainerSupport {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private PostCommentService service;
    @Autowired
    private JobEnqueuer enqueuer;
    @Autowired
    private TransactionTemplate tx;
    @Autowired
    private JdbcTemplate jdbc;

    private CommentFixtures f;
    private UUID author;
    private UUID member;
    private UUID outsider;
    private UUID room;
    private UUID post;

    @BeforeEach
    void seed() {
        f = new CommentFixtures(jdbc);
        author = f.profile("작성자");
        member = f.profile("배심원");
        outsider = f.profile("외부인");
        room = f.room(author);
        f.member(room, author);
        f.member(room, member);
        post = f.post(author);
        f.share(post, room);
    }

    private PostCommentResponse create(UUID userId, UUID roomId, String content) {
        return service.create(post, userId, new CreatePostCommentRequest(roomId == null ? null : roomId.toString(),
                content));
    }

    private static void assertRejected(Runnable call, HttpStatus status) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(PublicApiRejection.class, e -> assertThat(e.getStatus()).isEqualTo(status));
    }

    private int commentCount(UUID postId) {
        return jdbc.queryForObject("SELECT count(*) FROM post_comments WHERE post_id = ?", Integer.class, postId);
    }

    @Test
    @DisplayName("10 §3 JUDGED 게시물 댓글 → 같은 트랜잭션에서 RETAIN 1행(dedupe retain:comment:{id}:1, payload 네 키·verdict_id null)·retained_at")
    void judgedCommentRetainsImmediately() {
        f.judge(post);

        PostCommentResponse created = create(member, room, "택시는 좀 심했다");
        UUID commentId = UUID.fromString(created.id());

        List<CommentFixtures.RetainJob> jobs = f.retainJobs(commentId);
        assertThat(jobs).hasSize(1);
        assertThat(jobs.getFirst().dedupeKey()).isEqualTo("retain:comment:" + commentId + ":1");
        assertThat(jobs.getFirst().eventType()).isEqualTo("comment.approved");
        Map<String, Object> payload = JSON.readValue(jobs.getFirst().payload(), Map.class);
        assertThat(payload).containsOnlyKeys("event", "verdict_id", "comment_id", "version");
        Map<String, Object> expected = new HashMap<>();
        expected.put("event", "comment.approved");
        expected.put("verdict_id", null);
        expected.put("comment_id", commentId.toString());
        expected.put("version", 1);
        assertThat(payload).isEqualTo(expected);
        assertThat(f.retainedAt(commentId)).isNotNull();
    }

    @Test
    @DisplayName("10 §3 같은 댓글 id·version 으로 두 번 enqueue → ai.jobs 1행(ON CONFLICT DO NOTHING)")
    void duplicateEnqueueIsOneRow() {
        f.judge(post);
        UUID commentId = UUID.fromString(create(member, room, "한 번만 들어가야 함").id());

        EnqueuedJob again = tx.execute(s -> enqueuer.enqueueRetainComment(commentId.toString(), 1));

        assertThat(again.created()).isFalse();
        assertThat(f.retainJobs(commentId)).hasSize(1);
    }

    @Test
    @DisplayName("10 §3 투표 중(verdict 없음) 게시물 댓글 → RETAIN 0·retained_at NULL")
    void votingPostHasNoRetain() {
        UUID commentId = UUID.fromString(create(member, room, "아직 투표 중").id());

        assertThat(f.retainJobs(commentId)).isEmpty();
        assertThat(f.retainedAt(commentId)).isNull();
    }

    @Test
    @DisplayName("10 §3 판결이 dismissed(FINAL) 인 게시물 댓글 → JUDGED 아님, RETAIN 0")
    void dismissedPostHasNoRetain() {
        f.verdict(post, "dismissed", "FINAL");

        UUID commentId = UUID.fromString(create(member, room, "각하됨").id());

        assertThat(f.retainJobs(commentId)).isEmpty();
        assertThat(f.retainedAt(commentId)).isNull();
    }

    @Test
    @DisplayName("SPEC 201자 → 400·행 없음. 200자(코드 포인트, 이모지 포함)는 통과")
    void lengthLimitIsCodePoints() {
        assertRejected(() -> create(member, room, "가".repeat(201)), HttpStatus.BAD_REQUEST);
        assertThat(commentCount(post)).isZero();

        create(member, room, "가".repeat(200));
        // UTF-16 로는 400자지만 코드 포인트 200 이라 DB CHECK char_length 와 같게 통과
        create(member, room, "😀".repeat(200));
        assertThat(commentCount(post)).isEqualTo(2);
    }

    @Test
    @DisplayName("SPEC 빈 문자열·null·공백만 → 400·행 없음(B-2 ⑤)")
    void blankContentRejected() {
        assertRejected(() -> create(member, room, ""), HttpStatus.BAD_REQUEST);
        assertRejected(() -> create(member, room, null), HttpStatus.BAD_REQUEST);
        assertRejected(() -> create(member, room, "   \n\t"), HttpStatus.BAD_REQUEST);
        assertThat(commentCount(post)).isZero();
    }

    @Test
    @DisplayName("SPEC 앞뒤 공백은 지우지 않고 그대로 저장한다(B-2 ⑤)")
    void contentNotTrimmed() {
        PostCommentResponse created = create(member, room, "  공백 유지  ");

        assertThat(created.content()).isEqualTo("  공백 유지  ");
        assertThat(f.commentRow(UUID.fromString(created.id())).get("content")).isEqualTo("  공백 유지  ");
    }

    @Test
    @DisplayName("SPEC roomId 누락·UUID 아님 → 400(B-2 ⑦)")
    void roomIdMissingOrInvalid() {
        assertRejected(() -> create(member, null, "방이 없음"), HttpStatus.BAD_REQUEST);
        assertRejected(() -> service.create(post, member, new CreatePostCommentRequest("not-a-uuid", "방이 이상함")),
                HttpStatus.BAD_REQUEST);
        assertThat(commentCount(post)).isZero();
    }

    @Test
    @DisplayName("SPEC 공유 방 멤버지만 지정한 방(공유 안 된 방) 멤버 아님 → 403. 게시물을 볼 수 없는 사람 → 404(B-2 ③)")
    void notMemberOfRoom() {
        UUID otherRoom = f.room(outsider);
        f.member(otherRoom, outsider);
        f.member(otherRoom, member);

        // member 는 게시물을 볼 수 있지만 otherRoom 은 공유 방이 아니다
        assertRejected(() -> create(member, otherRoom, "공유 안 된 방"), HttpStatus.FORBIDDEN);
        // outsider 는 어느 활성 공유 방 멤버도 아니라 게시물 자체가 404
        assertRejected(() -> create(outsider, room, "남의 방"), HttpStatus.NOT_FOUND);
        // 404 가 400 보다 먼저
        assertRejected(() -> create(outsider, null, ""), HttpStatus.NOT_FOUND);
        // 403 이 content 검증 400 보다 먼저(빈 내용·201자여도 방 권한부터)
        assertRejected(() -> create(member, otherRoom, ""), HttpStatus.FORBIDDEN);
        assertRejected(() -> create(member, otherRoom, "가".repeat(201)), HttpStatus.FORBIDDEN);
        assertThat(commentCount(post)).isZero();
    }

    @Test
    @DisplayName("10 §8 공유가 철회된 방(revoked_at) 멤버 → 403(다른 활성 방으로는 볼 수 있음). 철회 방에만 속하면 404")
    void revokedRoomRejected() {
        UUID revokedRoom = f.room(author);
        f.member(revokedRoom, member);
        UUID onlyRevoked = f.profile();
        f.member(revokedRoom, onlyRevoked);
        f.share(post, revokedRoom);
        f.revoke(post, revokedRoom);

        assertRejected(() -> create(member, revokedRoom, "철회된 방"), HttpStatus.FORBIDDEN);
        assertRejected(() -> create(member, revokedRoom, ""), HttpStatus.FORBIDDEN);
        assertRejected(() -> create(onlyRevoked, revokedRoom, "철회된 방"), HttpStatus.NOT_FOUND);
        assertThat(commentCount(post)).isZero();
    }

    @Test
    @DisplayName("SPEC 게시물 작성자도 자기가 멤버인 활성 공유 방이면 댓글을 단다(B-2 ②)")
    void authorMayComment() {
        PostCommentResponse created = create(author, room, "변명합니다");

        assertThat(created.userId()).isEqualTo(author.toString());
        assertThat(created.nickname()).isEqualTo("작성자");
        assertThat(created.roomId()).isEqualTo(room.toString());
        assertThat(created.postId()).isEqualTo(post.toString());
        assertThat(created.createdAt()).isNotNull();
    }

    @Test
    @DisplayName("SPEC 삭제·없는 게시물에 작성 → 404")
    void deletedOrMissingPost() {
        f.deletePost(post);

        assertRejected(() -> create(member, room, "삭제된 게시물"), HttpStatus.NOT_FOUND);
        assertRejected(() -> service.create(UUID.randomUUID(), member,
                new CreatePostCommentRequest(room.toString(), "없는 게시물")), HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("SPEC 목록 — 작성자는 활성 공유 방 전체, 멤버는 자기 방 댓글만. 철회 방·삭제 댓글 제외, 오래된 순, 외부인 404(B-2 ①)")
    void listVisibility() {
        UUID roomB = f.room(author);
        UUID memberB = f.profile("비방");
        f.member(roomB, author);
        f.member(roomB, memberB);
        f.share(post, roomB);
        UUID roomC = f.room(author);
        UUID memberC = f.profile("씨방");
        f.member(roomC, memberC);
        f.member(roomC, member);
        f.share(post, roomC);

        String a1 = create(member, room, "A 첫째").id();
        String b1 = create(memberB, roomB, "B 첫째").id();
        String deleted = create(member, room, "A 지울 것").id();
        String c1 = create(memberC, roomC, "C 첫째").id();
        String a2 = create(author, room, "A 둘째").id();
        service.delete(post, UUID.fromString(deleted), member);
        f.revoke(post, roomC);

        assertThat(service.list(post, author, null)).extracting(PostCommentResponse::id).containsExactly(a1, b1, a2);
        // member 는 room 과 roomC 멤버지만 roomC 는 철회됐다
        assertThat(service.list(post, member, null)).extracting(PostCommentResponse::id).containsExactly(a1, a2);
        assertThat(service.list(post, memberB, null)).extracting(PostCommentResponse::id).containsExactly(b1);
        assertThat(service.list(post, member, null).getFirst().nickname()).isEqualTo("배심원");
        // 철회된 방에만 속한 사람은 게시물을 볼 수 없다
        assertRejected(() -> service.list(post, memberC, null), HttpStatus.NOT_FOUND);
        assertRejected(() -> service.list(post, outsider, null), HttpStatus.NOT_FOUND);
        assertRejected(() -> service.list(UUID.randomUUID(), author, null), HttpStatus.NOT_FOUND);
        assertThat(c1).isNotNull();
    }

    @Test
    @DisplayName("10 §8 본인 삭제 → post:{post_id} epoch +1·deleted_at·privacy_invalidations ('COMMENT', id) 가 한 트랜잭션(롤백하면 셋 다 없다)")
    void deleteIsOneTransaction() {
        UUID commentId = UUID.fromString(create(member, room, "지울 댓글").id());
        String key = ScopeKeys.post(post);
        long before = f.epoch(key);

        tx.executeWithoutResult(s -> {
            service.delete(post, commentId, member);
            assertThat(f.epoch(key)).isEqualTo(before + 1);
            assertThat(f.commentRow(commentId).get("deleted_at")).isNotNull();
            assertThat(f.invalidations(commentId)).containsExactly(List.of(key, "COMMENT"));
            s.setRollbackOnly();
        });

        assertThat(f.epoch(key)).isEqualTo(before);
        assertThat(f.commentRow(commentId).get("deleted_at")).isNull();
        assertThat(f.invalidations(commentId)).isEmpty();

        service.delete(post, commentId, member);

        assertThat(f.epoch(key)).isEqualTo(before + 1);
        assertThat(f.commentRow(commentId).get("deleted_at")).isNotNull();
        assertThat(f.invalidations(commentId)).containsExactly(List.of(key, "COMMENT"));
    }

    @Test
    @DisplayName("10 §8 이미 삭제된 댓글 재삭제 → 성공, epoch·기록 추가 없음")
    void deleteAgainIsNoop() {
        UUID commentId = UUID.fromString(create(member, room, "두 번 지움").id());
        String key = ScopeKeys.post(post);
        service.delete(post, commentId, member);
        long afterFirst = f.epoch(key);
        Object deletedAt = f.commentRow(commentId).get("deleted_at");

        service.delete(post, commentId, member);

        assertThat(f.epoch(key)).isEqualTo(afterFirst);
        assertThat(f.invalidations(commentId)).hasSize(1);
        assertThat(f.commentRow(commentId).get("deleted_at")).isEqualTo(deletedAt);
    }

    @Test
    @DisplayName("10 §8 타인 댓글 삭제 → 403, epoch·deleted_at·기록 불변(게시물 작성자여도)")
    void deleteOthersRejected() {
        UUID commentId = UUID.fromString(create(member, room, "남이 못 지움").id());
        String key = ScopeKeys.post(post);
        long before = f.epoch(key);

        assertRejected(() -> service.delete(post, commentId, author), HttpStatus.FORBIDDEN);

        assertThat(f.epoch(key)).isEqualTo(before);
        assertThat(f.commentRow(commentId).get("deleted_at")).isNull();
        assertThat(f.invalidations(commentId)).isEmpty();
    }

    @Test
    @DisplayName("SPEC 삭제 404 — 게시물 없음·삭제, 댓글 없음, 다른 게시물의 댓글(B-2 ⑥). 볼 수 없는 타인도 404")
    void deleteNotFound() {
        UUID commentId = UUID.fromString(create(member, room, "여기 있음").id());
        UUID otherPost = f.post(author);
        f.share(otherPost, room);

        assertRejected(() -> service.delete(otherPost, commentId, member), HttpStatus.NOT_FOUND);
        assertRejected(() -> service.delete(post, UUID.randomUUID(), member), HttpStatus.NOT_FOUND);
        assertRejected(() -> service.delete(UUID.randomUUID(), commentId, member), HttpStatus.NOT_FOUND);
        assertRejected(() -> service.delete(post, commentId, outsider), HttpStatus.NOT_FOUND);
        assertThat(f.commentRow(commentId).get("deleted_at")).isNull();

        f.deletePost(post);
        assertRejected(() -> service.delete(post, commentId, member), HttpStatus.NOT_FOUND);
        assertThat(f.commentRow(commentId).get("deleted_at")).isNull();
    }

    @Test
    @DisplayName("10 §8 댓글 방 공유가 철회돼 게시물을 볼 수 없게 돼도 본인은 삭제할 수 있다(B-2 ⑥)")
    void ownerDeletesAfterRevoke() {
        UUID commentId = UUID.fromString(create(member, room, "철회 뒤 지움").id());
        f.revoke(post, room);
        assertRejected(() -> service.list(post, member, null), HttpStatus.NOT_FOUND);

        service.delete(post, commentId, member);

        assertThat(f.commentRow(commentId).get("deleted_at")).isNotNull();
        assertThat(f.invalidations(commentId)).containsExactly(List.of(ScopeKeys.post(post), "COMMENT"));
    }
}
