package com.ttegeoji.backend.comment;

import com.ttegeoji.backend.comment.dto.CreatePostCommentRequest;
import com.ttegeoji.backend.jobs.JobEnqueuer;
import com.ttegeoji.backend.support.PostgresContainerSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

// test 프로필에는 스케줄러 빈이 없다. 인스턴스를 직접 만들어 스캔을 부른다. SKIP LOCKED·동시 스캔을 보려고 커밋한다.
// 공유 컨테이너에 다른 테스트의 댓글이 있을 수 있어 스캔 반환값이 아니라 자기 댓글 id 의 RETAIN 행으로 센다
@SpringBootTest
class CommentRetainSchedulerTest extends PostgresContainerSupport {

    @Autowired
    private CommentQueries queries;
    @Autowired
    private JobEnqueuer enqueuer;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private PostCommentService service;
    @Autowired
    private JdbcTemplate jdbc;

    private CommentFixtures f;
    private CommentRetainScheduler scheduler;
    private UUID author;
    private UUID member;
    private UUID room;
    private UUID post;

    @BeforeEach
    void seed() {
        f = new CommentFixtures(jdbc);
        scheduler = new CommentRetainScheduler(queries, enqueuer, transactionManager);
        author = f.profile();
        member = f.profile();
        room = f.room(author);
        f.member(room, author);
        f.member(room, member);
        post = f.post(author);
        f.share(post, room);
    }

    @Test
    @DisplayName("10 §3 투표 중 댓글 2개 → verdict FINAL 저장 → 스캔 1회 → RETAIN 2·retained_at 채움. 다시 스캔 → 추가 0")
    void retainsAfterJudged() {
        UUID c1 = f.comment(post, room, member, "첫째 댓글");
        UUID c2 = f.comment(post, room, author, "둘째 댓글");
        scheduler.runCycle();
        assertThat(f.retainJobs(c1)).isEmpty();
        assertThat(f.retainJobs(c2)).isEmpty();

        f.judge(post);
        scheduler.runCycle();

        for (UUID c : List.of(c1, c2)) {
            assertThat(f.retainJobs(c)).extracting(CommentFixtures.RetainJob::dedupeKey)
                    .containsExactly("retain:comment:" + c + ":1");
            assertThat(f.retainedAt(c)).isNotNull();
        }
        OffsetDateTime firstRetained = f.retainedAt(c1);

        scheduler.runCycle();

        assertThat(f.retainJobs(c1)).hasSize(1);
        assertThat(f.retainJobs(c2)).hasSize(1);
        assertThat(f.retainedAt(c1)).isEqualTo(firstRetained);
    }

    @Test
    @DisplayName("10 §3 dismissed(FINAL) 게시물 댓글 → 스캔해도 0")
    void dismissedNotRetained() {
        UUID c = f.comment(post, room, member, "각하 게시물");
        f.verdict(post, "dismissed", "FINAL");

        scheduler.runCycle();

        assertThat(f.retainJobs(c)).isEmpty();
        assertThat(f.retainedAt(c)).isNull();
    }

    @Test
    @DisplayName("10 §3 판결 PENDING(형량 미확정) 게시물 댓글 → 스캔해도 0")
    void pendingSentenceNotRetained() {
        UUID c = f.comment(post, room, member, "형량 대기");
        // policy_snapshot '{}' 라 JuryScheduler 게이트(POLICY_VALID)가 SENTENCE 를 넣지 않는다
        f.verdict(post, "guilty", "PENDING");

        scheduler.runCycle();

        assertThat(f.retainJobs(c)).isEmpty();
    }

    @Test
    @DisplayName("10 §3·§8 삭제 댓글·삭제 게시물 → 스캔해도 0")
    void deletedNotRetained() {
        UUID deletedComment = f.comment(post, room, member, "지운 댓글");
        f.deleteComment(deletedComment);
        UUID otherPost = f.post(author);
        f.share(otherPost, room);
        UUID onDeletedPost = f.comment(otherPost, room, member, "지운 게시물의 댓글");
        f.judge(post);
        f.judge(otherPost);
        f.deletePost(otherPost);

        scheduler.runCycle();

        assertThat(f.retainJobs(deletedComment)).isEmpty();
        assertThat(f.retainJobs(onDeletedPost)).isEmpty();
        assertThat(f.retainedAt(deletedComment)).isNull();
        assertThat(f.retainedAt(onDeletedPost)).isNull();
    }

    @Test
    @DisplayName("10 §3 확정 뒤 작성 즉시 RETAIN 된 댓글 → 스캔이 다시 잡지 않는다(retained_at 불변·job 1)")
    void alreadyRetainedSkipped() {
        f.judge(post);
        UUID c = UUID.fromString(service.create(post, member,
                new CreatePostCommentRequest(room.toString(), "확정 뒤 댓글")).id());
        OffsetDateTime retainedAt = f.retainedAt(c);
        assertThat(retainedAt).isNotNull();

        scheduler.runCycle();

        assertThat(f.retainJobs(c)).hasSize(1);
        assertThat(f.retainedAt(c)).isEqualTo(retainedAt);
    }

    @Test
    @DisplayName("10 §3 두 인스턴스 동시 스캔 → 댓글당 RETAIN 1(FOR UPDATE OF c SKIP LOCKED + dedupe)")
    void concurrentScansDoNotDuplicate() throws Exception {
        List<UUID> comments = List.of(
                f.comment(post, room, member, "동시 1"),
                f.comment(post, room, member, "동시 2"),
                f.comment(post, room, author, "동시 3"));
        f.judge(post);
        CommentRetainScheduler other = new CommentRetainScheduler(queries, enqueuer, transactionManager);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> a = pool.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return scheduler.retainJudged();
            });
            Future<Integer> b = pool.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return other.retainJudged();
            });
            start.countDown();
            a.get(20, TimeUnit.SECONDS);
            b.get(20, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        for (UUID c : comments) {
            assertThat(f.retainJobs(c)).hasSize(1);
            assertThat(f.retainedAt(c)).isNotNull();
        }
    }

    @Test
    @DisplayName("10 §3 운영 배선 — runCycle @Scheduled(fixedDelay 5000)·@Profile(!test)·@Component")
    void wiring() throws Exception {
        Scheduled scheduled = CommentRetainScheduler.class.getMethod("runCycle").getAnnotation(Scheduled.class);
        assertThat(scheduled).isNotNull();
        assertThat(scheduled.fixedDelay()).isEqualTo(5000L);
        assertThat(CommentRetainScheduler.class.getAnnotation(Profile.class).value()).containsExactly("!test");
        assertThat(CommentRetainScheduler.class.getAnnotation(Component.class)).isNotNull();
    }
}
