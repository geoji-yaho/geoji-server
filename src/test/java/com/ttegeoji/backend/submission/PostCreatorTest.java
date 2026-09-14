package com.ttegeoji.backend.submission;

import com.ttegeoji.backend.ai.IntakeClient.IntakeResult;
import com.ttegeoji.backend.ai.IntakeClient.Mode;
import com.ttegeoji.backend.domain.enums.IntakeStatus;
import com.ttegeoji.backend.domain.enums.PostType;
import com.ttegeoji.backend.jobs.JobEnqueuer;
import com.ttegeoji.backend.support.PostgresContainerSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

// PostCreator 는 스스로 커밋하므로 테스트 트랜잭션을 두지 않는다. id 는 매번 새로 만들어 공유 컨테이너에서 섞이지 않는다
@SpringBootTest
class PostCreatorTest extends PostgresContainerSupport {

    @Autowired
    private PostCreator postCreator;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @MockitoSpyBean
    private JobEnqueuer jobEnqueuer;

    private final SubmissionFixtures fixtures = new SubmissionFixtures();

    private SubmissionPayload payload(PostType type, List<UUID> rooms) {
        return SubmissionPayload.normalize(type, 12000, "배달", " 치킨 ", "야식", rooms);
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    @Test
    @DisplayName("10 §3·§9 게시물 저장 — posts(version 1·audience_version 1·intake·submission_id)·post_rooms·PREPARE·submission COMPLETED")
    void createsPostRoomsAndPrepare() {
        UUID author = fixtures.profile(jdbc);
        UUID roomA = fixtures.room(jdbc, author, 30);
        UUID roomB = fixtures.room(jdbc, author, 60);
        UUID submission = fixtures.submission(jdbc, author);
        SubmissionPayload payload = payload(PostType.spent, List.of(roomB, roomA));

        UUID postId = postCreator.create(submission, payload, IntakeResult.fallback(Mode.INITIAL), IntakeStatus.PASS);

        Map<String, Object> post = jdbc.queryForMap("SELECT * FROM posts WHERE id = ?", postId);
        assertThat(post.get("author_id")).isEqualTo(author);
        assertThat(post.get("item")).isEqualTo("치킨");
        assertThat(post.get("version")).isEqualTo(1);
        assertThat(post.get("audience_version")).isEqualTo(1);
        assertThat(post.get("intake_status")).isEqualTo("PASS");
        assertThat(post.get("intake_source")).isEqualTo("FALLBACK");
        assertThat(post.get("submission_id")).isEqualTo(submission);
        assertThat(jdbc.queryForList("SELECT room_id FROM post_rooms WHERE post_id = ?", UUID.class, postId))
                .containsExactlyInAnyOrder(roomA, roomB);

        Map<String, Object> job = jdbc.queryForMap(
                "SELECT kind, dedupe_key, payload::text AS payload FROM ai.jobs WHERE aggregate_id = ?", postId.toString());
        assertThat(job.get("kind")).isEqualTo("PREPARE");
        assertThat(job.get("dedupe_key")).isEqualTo("prepare:" + postId + ":1:1");

        Map<String, Object> sub = jdbc.queryForMap("SELECT status, post_id, payload_hash, intake_result::text AS ir FROM submissions WHERE id = ?", submission);
        assertThat(sub.get("status")).isEqualTo("COMPLETED");
        assertThat(sub.get("post_id")).isEqualTo(postId);
        assertThat(sub.get("payload_hash")).isEqualTo(payload.hash());
        assertThat((String) sub.get("ir")).contains("\"intake_source\": \"FALLBACK\"");
    }

    @Test
    @DisplayName("10 §9 vote_deadline_at = 생성 시각 + 공유 방 중 가장 짧은 vote_deadline_minutes")
    void voteDeadlineUsesShortestRoom() {
        UUID author = fixtures.profile(jdbc);
        UUID slow = fixtures.room(jdbc, author, 120);
        UUID fast = fixtures.room(jdbc, author, 15);
        UUID submission = fixtures.submission(jdbc, author);

        UUID postId = postCreator.create(submission, payload(PostType.considering, List.of(slow, fast)),
                IntakeResult.fallback(Mode.INITIAL), IntakeStatus.PASS);

        Map<String, Object> post = jdbc.queryForMap("SELECT created_at, vote_deadline_at FROM posts WHERE id = ?", postId);
        OffsetDateTime createdAt = ((java.sql.Timestamp) post.get("created_at")).toInstant().atOffset(java.time.ZoneOffset.UTC);
        OffsetDateTime deadline = ((java.sql.Timestamp) post.get("vote_deadline_at")).toInstant().atOffset(java.time.ZoneOffset.UTC);
        assertThat(Duration.between(createdAt, deadline)).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    @DisplayName("10 §13 작업 7 중복 완료 — 같은 submission 두 번 → 같은 post, PREPARE 1개")
    void duplicateCreateReturnsExisting() {
        UUID author = fixtures.profile(jdbc);
        UUID room = fixtures.room(jdbc, author, 30);
        UUID submission = fixtures.submission(jdbc, author);
        SubmissionPayload payload = payload(PostType.spent, List.of(room));

        UUID first = postCreator.create(submission, payload, IntakeResult.fallback(Mode.INITIAL), IntakeStatus.PASS);
        UUID second = postCreator.create(submission, payload, IntakeResult.fallback(Mode.INITIAL), IntakeStatus.PASS);

        assertThat(second).isEqualTo(first);
        assertThat(count("SELECT count(*) FROM posts WHERE submission_id = ?", submission)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM ai.jobs WHERE dedupe_key = ?", "prepare:" + first + ":1:1")).isEqualTo(1);
    }

    @Test
    @DisplayName("10 §3 한 트랜잭션 — PREPARE INSERT 뒤 실패하면 posts·post_rooms·job 모두 없음")
    void failureAfterPrepareRollsBackAll() {
        UUID author = fixtures.profile(jdbc);
        UUID room = fixtures.room(jdbc, author, 30);
        UUID submission = fixtures.submission(jdbc, author);
        AtomicReference<String> postId = new AtomicReference<>();
        // 스파이 앞에 MANDATORY 트랜잭션 프록시가 있어 스텁 등록 호출도 트랜잭션 안에서 한다
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                doAnswer(invocation -> {
                    invocation.callRealMethod();
                    postId.set(invocation.getArgument(0));
                    throw new IllegalStateException("주입한 실패");
                }).when(jobEnqueuer).enqueuePrepare(anyString(), anyLong(), anyLong()));

        try {
            assertThatThrownBy(() -> postCreator.create(submission, payload(PostType.spent, List.of(room)),
                    IntakeResult.fallback(Mode.INITIAL), IntakeStatus.PASS))
                    .hasMessage("주입한 실패");
        } finally {
            reset(jobEnqueuer);
        }

        assertThat(postId.get()).isNotNull();
        assertThat(count("SELECT count(*) FROM posts WHERE submission_id = ?", submission)).isZero();
        assertThat(count("SELECT count(*) FROM post_rooms WHERE post_id = ?::uuid", postId.get())).isZero();
        assertThat(count("SELECT count(*) FROM ai.jobs WHERE aggregate_id = ?", postId.get())).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM submissions WHERE id = ?", String.class, submission))
                .isEqualTo("NEW");
    }

    @Test
    @DisplayName("10 §3 post_type 은 spent·considering 두 값뿐 — 둘 다 PREPARE, NO_SPEND 는 posts 에 없음(해당 없음)")
    void bothPostTypesEnqueuePrepare() {
        assertThat(PostType.values()).containsExactly(PostType.spent, PostType.considering);

        UUID author = fixtures.profile(jdbc);
        UUID room = fixtures.room(jdbc, author, 30);
        UUID submission = fixtures.submission(jdbc, author);
        UUID postId = postCreator.create(submission, payload(PostType.considering, List.of(room)),
                IntakeResult.fallback(Mode.INITIAL), IntakeStatus.UNCLARIFIED);

        assertThat(count("SELECT count(*) FROM ai.jobs WHERE dedupe_key = ?", "prepare:" + postId + ":1:1")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT intake_status FROM posts WHERE id = ?", String.class, postId))
                .isEqualTo("UNCLARIFIED");
    }
}
