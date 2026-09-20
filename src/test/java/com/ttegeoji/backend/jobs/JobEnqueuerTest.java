package com.ttegeoji.backend.jobs;

import com.ttegeoji.backend.domain.enums.SpiceLevel;
import com.ttegeoji.backend.support.PostgresContainerSupport;
import com.ttegeoji.backend.util.Json;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 테스트마다 트랜잭션을 열고 롤백한다. 공유 컨테이너에 job 을 커밋하지 않아 다른 테스트·백그라운드 reaper 와 섞이지 않는다.
@SpringBootTest
@Transactional
class JobEnqueuerTest extends PostgresContainerSupport {

    @Autowired
    private JobEnqueuer enqueuer;
    @Autowired
    private JobQueries queries;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private static String id() {
        return UUID.randomUUID().toString();
    }

    private JobRow row(EnqueuedJob job) {
        return queries.findJob(job.id()).orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> payload(JobRow row) {
        return (Map<String, Object>) Json.read(row.payload());
    }

    private OffsetDateTime dbNowPlus(int seconds) {
        return jdbcTemplate.queryForObject("SELECT now() + make_interval(secs => ?)", OffsetDateTime.class, seconds);
    }

    private int countByDedupe(String dedupeKey) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ai.jobs WHERE dedupe_key = ?", Integer.class, dedupeKey);
        return count == null ? 0 : count;
    }

    @Test
    @DisplayName("19 §3 JURY_VOTE jury.vote_requested — dedupe post·room·voter·priority 60·max_attempts 2·deadline null·payload 4키")
    void juryVoteRow() {
        String postId = id();
        String roomId = id();
        String voterId = id();
        JobRow row = row(enqueuer.enqueueJuryVote(postId, 2, roomId, voterId));

        assertThat(row.kind()).isEqualTo(JobKind.JURY_VOTE);
        assertThat(row.eventType()).isEqualTo("jury.vote_requested");
        assertThat(row.dedupeKey()).isEqualTo("jury-vote:" + postId + ":" + roomId + ":" + voterId);
        assertThat(row.priority()).isEqualTo(60);
        assertThat(row.maxAttempts()).isEqualTo(2);
        assertThat(row.deadlineAt()).isNull();
        assertThat(row.aggregateId()).isEqualTo(postId);
        assertThat(row.aggregateVersion()).isEqualTo(2);
        assertThat(payload(row)).containsExactlyInAnyOrderEntriesOf(
                Map.of("post_id", postId, "post_version", 2, "room_id", roomId, "voter_id", voterId));
        // 같은 글·방·봇은 1개
        assertThat(enqueuer.enqueueJuryVote(postId, 2, roomId, voterId).created()).isFalse();
        assertThat(countByDedupe("jury-vote:" + postId + ":" + roomId + ":" + voterId)).isEqualTo(1);
    }

    @Test
    @DisplayName("10 §3 PREPARE post.created — dedupe·priority 30·max_attempts 2·deadline null·payload")
    void prepareRow() {
        String postId = id();
        JobRow row = row(enqueuer.enqueuePrepare(postId, 3, 2));

        assertThat(row.kind()).isEqualTo(JobKind.PREPARE);
        assertThat(row.eventType()).isEqualTo("post.created");
        assertThat(row.dedupeKey()).isEqualTo("prepare:" + postId + ":3:2");
        assertThat(row.priority()).isEqualTo(30);
        assertThat(row.maxAttempts()).isEqualTo(2);
        assertThat(row.deadlineAt()).isNull();
        assertThat(row.aggregateId()).isEqualTo(postId);
        assertThat(row.aggregateVersion()).isEqualTo(3);
        assertThat(row.status()).isEqualTo("QUEUED");
        assertThat(row.traceId()).isNotBlank();
        assertThat(payload(row)).containsExactlyInAnyOrderEntriesOf(
                Map.of("post_id", postId, "post_version", 3, "audience_version", 2));
    }

    @Test
    @DisplayName("10 §3 SENTENCE verdict.confirmed — dedupe·priority 100·max_attempts 2·payload·deadline = DB now() + SENTENCE 마감")
    void sentenceRow() {
        String verdictId = id();
        String postId = id();
        EnqueuedJob job = enqueuer.enqueueSentence(verdictId, 4, postId, "trace-given");
        JobRow row = row(job);

        assertThat(job.created()).isTrue();
        assertThat(row.kind()).isEqualTo(JobKind.SENTENCE);
        assertThat(row.eventType()).isEqualTo("verdict.confirmed");
        assertThat(row.dedupeKey()).isEqualTo("sentence:" + verdictId + ":4");
        assertThat(row.priority()).isEqualTo(100);
        assertThat(row.maxAttempts()).isEqualTo(2);
        assertThat(row.aggregateId()).isEqualTo(verdictId);
        assertThat(row.aggregateVersion()).isEqualTo(4);
        assertThat(row.traceId()).isEqualTo("trace-given");
        assertThat(payload(row)).containsExactlyInAnyOrderEntriesOf(
                Map.of("verdict_id", verdictId, "verdict_version", 4, "post_id", postId));
        // 같은 트랜잭션 안의 now() 는 고정이라 정확히 같다(10 §3 D-24 INSERT 시각 + 10s)
        assertThat(job.deadlineAt().toInstant()).isEqualTo(dbNowPlus(JobKind.SENTENCE.deadlineAfterSeconds()).toInstant());
        assertThat(row.deadlineAt().toInstant()).isEqualTo(job.deadlineAt().toInstant());
    }

    @Test
    @DisplayName("10 §3 RETAIN sentence.finalized — dedupe·priority 10·max_attempts 5·네 키(comment_id null)")
    void retainVerdictRow() {
        String verdictId = id();
        JobRow row = row(enqueuer.enqueueRetainVerdict(verdictId, 7));

        assertThat(row.kind()).isEqualTo(JobKind.RETAIN);
        assertThat(row.eventType()).isEqualTo("sentence.finalized");
        assertThat(row.dedupeKey()).isEqualTo("retain:verdict:" + verdictId + ":7");
        assertThat(row.priority()).isEqualTo(10);
        assertThat(row.maxAttempts()).isEqualTo(5);
        assertThat(row.deadlineAt()).isNull();
        assertThat(row.aggregateId()).isEqualTo(verdictId);
        assertThat(row.aggregateVersion()).isEqualTo(7);
        Map<String, Object> payload = payload(row);
        assertThat(payload).containsOnlyKeys("event", "verdict_id", "comment_id", "version");
        assertThat(payload).containsEntry("event", "sentence.finalized")
                .containsEntry("verdict_id", verdictId)
                .containsEntry("comment_id", null)
                .containsEntry("version", 7);
    }

    @Test
    @DisplayName("10 §3 RETAIN comment.approved — dedupe·priority 10·max_attempts 5·네 키(verdict_id null)")
    void retainCommentRow() {
        String commentId = id();
        JobRow row = row(enqueuer.enqueueRetainComment(commentId, 2));

        assertThat(row.kind()).isEqualTo(JobKind.RETAIN);
        assertThat(row.eventType()).isEqualTo("comment.approved");
        assertThat(row.dedupeKey()).isEqualTo("retain:comment:" + commentId + ":2");
        assertThat(row.priority()).isEqualTo(10);
        assertThat(row.maxAttempts()).isEqualTo(5);
        assertThat(row.deadlineAt()).isNull();
        assertThat(row.aggregateId()).isEqualTo(commentId);
        assertThat(row.aggregateVersion()).isEqualTo(2);
        Map<String, Object> payload = payload(row);
        assertThat(payload).containsOnlyKeys("event", "verdict_id", "comment_id", "version");
        assertThat(payload).containsEntry("event", "comment.approved")
                .containsEntry("verdict_id", null)
                .containsEntry("comment_id", commentId)
                .containsEntry("version", 2);
    }

    @Test
    @DisplayName("10 §3 TEXT_RETRY verdict.text_retry — dedupe·priority 50·max_attempts 1·intensities·deadline = DB now() + TEXT_RETRY 마감")
    void textRetryRow() {
        String verdictId = id();
        EnqueuedJob job = enqueuer.enqueueTextRetry(verdictId, 5, 2, List.of(SpiceLevel.spicy, SpiceLevel.hell));
        JobRow row = row(job);

        assertThat(row.kind()).isEqualTo(JobKind.TEXT_RETRY);
        assertThat(row.eventType()).isEqualTo("verdict.text_retry");
        assertThat(row.dedupeKey()).isEqualTo("text-retry:" + verdictId + ":5:2");
        assertThat(row.priority()).isEqualTo(50);
        assertThat(row.maxAttempts()).isEqualTo(1);
        assertThat(row.aggregateId()).isEqualTo(verdictId);
        assertThat(row.aggregateVersion()).isEqualTo(5);
        assertThat(payload(row)).containsExactlyInAnyOrderEntriesOf(Map.of(
                "verdict_id", verdictId, "verdict_version", 5, "round", 2,
                "intensities", List.of("spicy", "hell")));
        assertThat(job.deadlineAt().toInstant()).isEqualTo(dbNowPlus(JobKind.TEXT_RETRY.deadlineAfterSeconds()).toInstant());
        assertThat(row.deadlineAt().toInstant()).isEqualTo(job.deadlineAt().toInstant());
    }

    @Test
    @DisplayName("10 §3 TEXT_RETRY intensities null → payload 에 키 없음")
    void textRetryWithoutIntensities() {
        String verdictId = id();
        JobRow row = row(enqueuer.enqueueTextRetry(verdictId, 1, 1, null));

        assertThat(payload(row)).containsOnlyKeys("verdict_id", "verdict_version", "round");
    }

    @Test
    @DisplayName("10 §3 TEXT_RETRY intensities 빈 배열 → 예외, job 없음")
    void textRetryEmptyIntensitiesRejected() {
        String verdictId = id();
        assertThatThrownBy(() -> enqueuer.enqueueTextRetry(verdictId, 1, 1, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(countByDedupe("text-retry:" + verdictId + ":1:1")).isZero();
    }

    @Test
    @DisplayName("10 §3 같은 dedupe_key 두 번 → 행 1개, 같은 id·deadline 반환")
    void sameDedupeTwice() {
        String verdictId = id();
        String postId = id();
        EnqueuedJob first = enqueuer.enqueueSentence(verdictId, 1, postId);
        EnqueuedJob second = enqueuer.enqueueSentence(verdictId, 1, postId);

        assertThat(first.created()).isTrue();
        assertThat(second.created()).isFalse();
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.deadlineAt()).isEqualTo(first.deadlineAt());
        assertThat(countByDedupe("sentence:" + verdictId + ":1")).isEqualTo(1);
    }

    @Test
    @DisplayName("10 §1 backend role 로 ai.jobs INSERT 가능")
    void backendRoleCanInsert() {
        jdbcTemplate.execute("SET LOCAL ROLE backend");
        String postId = id();
        EnqueuedJob job = enqueuer.enqueuePrepare(postId, 1, 1);

        assertThat(job.created()).isTrue();
        assertThat(countByDedupe("prepare:" + postId + ":1:1")).isEqualTo(1);
    }

    @Test
    @DisplayName("10 §1 ai_worker role 은 ai.jobs INSERT 불가")
    void aiWorkerRoleCannotInsert() {
        jdbcTemplate.execute("SET LOCAL ROLE ai_worker");
        assertThatThrownBy(() -> enqueuer.enqueuePrepare(id(), 1, 1))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .isInstanceOfSatisfying(SQLException.class,
                        e -> assertThat(e.getSQLState()).isEqualTo("42501")); // insufficient_privilege
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("10 §3 업무 트랜잭션 밖 호출 → IllegalTransactionStateException, job 없음")
    void outsideTransactionRejected() {
        String postId = id();
        assertThatThrownBy(() -> enqueuer.enqueuePrepare(postId, 1, 1))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(countByDedupe("prepare:" + postId + ":1:1")).isZero();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("10 §3 호출자 트랜잭션이 롤백되면 job 도 없다")
    void callerRollbackRemovesJob() {
        String verdictId = id();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            enqueuer.enqueueSentence(verdictId, 1, id());
            assertThat(countByDedupe("sentence:" + verdictId + ":1")).isEqualTo(1);
            status.setRollbackOnly();
        });
        assertThat(countByDedupe("sentence:" + verdictId + ":1")).isZero();
    }
}
