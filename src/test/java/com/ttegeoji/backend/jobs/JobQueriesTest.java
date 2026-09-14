package com.ttegeoji.backend.jobs;

import com.ttegeoji.backend.support.PostgresContainerSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// 행은 테스트 트랜잭션 안에서만 보인다(롤백). 백그라운드 reaper 가 만료 행을 먼저 회수하지 못한다.
@SpringBootTest
@Transactional
class JobQueriesTest extends PostgresContainerSupport {

    @Autowired
    private JobQueries queries;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private JobRowFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new JobRowFixtures(jdbcTemplate);
    }

    private static String postPayload(String postId) {
        return "{\"post_id\": \"" + postId + "\"}";
    }

    private static String verdictPayload(String verdictId) {
        return "{\"verdict_id\": \"" + verdictId + "\"}";
    }

    @Test
    @DisplayName("10 §4.1 RUNNING ∧ generation 일치 ∧ lease 유효 → 찾는다")
    void validLeaseFound() {
        UUID generationId = UUID.randomUUID();
        UUID jobId = fixtures.insert(new JobRowFixtures.Spec(JobKind.SENTENCE, "RUNNING", "{}", 1, 2, null,
                "now() + interval '30 seconds'", generationId, null));

        assertThat(queries.findRunningWithValidLease(jobId, generationId))
                .get().extracting(JobRow::id).isEqualTo(jobId);
    }

    @Test
    @DisplayName("10 §4.1 lease 만료 RUNNING → 빈 결과")
    void expiredLeaseEmpty() {
        UUID generationId = UUID.randomUUID();
        UUID jobId = fixtures.insert(new JobRowFixtures.Spec(JobKind.SENTENCE, "RUNNING", "{}", 1, 2, null,
                "now() - interval '1 second'", generationId, null));

        assertThat(queries.findRunningWithValidLease(jobId, generationId)).isEmpty();
    }

    @Test
    @DisplayName("10 §4.1 generation 불일치 → 빈 결과")
    void generationMismatchEmpty() {
        UUID jobId = fixtures.insert(new JobRowFixtures.Spec(JobKind.SENTENCE, "RUNNING", "{}", 1, 2, null,
                "now() + interval '30 seconds'", UUID.randomUUID(), null));

        assertThat(queries.findRunningWithValidLease(jobId, UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("10 §4.1 RUNNING 아님(QUEUED) → 빈 결과")
    void notRunningEmpty() {
        UUID jobId = fixtures.withPayload(JobKind.SENTENCE, "QUEUED", "{}");

        assertThat(queries.findRunningWithValidLease(jobId, UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("10 §3 D-24 isPrepareActive — QUEUED·RUNNING 참")
    void prepareActiveWhenQueuedOrRunning() {
        String queued = UUID.randomUUID().toString();
        String running = UUID.randomUUID().toString();
        fixtures.withPayload(JobKind.PREPARE, "QUEUED", postPayload(queued));
        fixtures.withPayload(JobKind.PREPARE, "RUNNING", postPayload(running));

        assertThat(queries.isPrepareActive(queued)).isTrue();
        assertThat(queries.isPrepareActive(running)).isTrue();
    }

    @Test
    @DisplayName("10 §3 D-24 isPrepareActive — SUCCEEDED·FAILED·CANCELLED·없음 거짓")
    void prepareInactiveWhenFinishedOrMissing() {
        for (String status : List.of("SUCCEEDED", "FAILED", "CANCELLED")) {
            String postId = UUID.randomUUID().toString();
            fixtures.withPayload(JobKind.PREPARE, status, postPayload(postId));
            assertThat(queries.isPrepareActive(postId)).as(status).isFalse();
        }
        assertThat(queries.isPrepareActive(UUID.randomUUID().toString())).isFalse();
    }

    @Test
    @DisplayName("10 §3 D-24 isPrepareActive — 같은 post 의 SENTENCE 는 PREPARE 로 세지 않는다")
    void prepareActiveIgnoresOtherKinds() {
        String postId = UUID.randomUUID().toString();
        fixtures.withPayload(JobKind.SENTENCE, "QUEUED", postPayload(postId));

        assertThat(queries.isPrepareActive(postId)).isFalse();
    }

    @Test
    @DisplayName("10 §8 D-26 cancelActiveForPost — 그 post 의 진행 중 PREPARE·SENTENCE 와 verdict 의 TEXT_RETRY 만 CANCELLED, 세 컬럼 NULL")
    void cancelActiveForPost() {
        String postId = UUID.randomUUID().toString();
        String verdictId = UUID.randomUUID().toString();
        String otherPost = UUID.randomUUID().toString();
        String otherVerdict = UUID.randomUUID().toString();

        UUID prepareQueued = fixtures.withPayload(JobKind.PREPARE, "QUEUED", postPayload(postId));
        UUID sentenceRunning = fixtures.withPayload(JobKind.SENTENCE, "RUNNING",
                "{\"verdict_id\": \"" + verdictId + "\", \"post_id\": \"" + postId + "\"}");
        UUID textRetryRunning = fixtures.withPayload(JobKind.TEXT_RETRY, "RUNNING", verdictPayload(verdictId));
        UUID textRetryQueued = fixtures.withPayload(JobKind.TEXT_RETRY, "QUEUED", verdictPayload(verdictId));

        UUID retainQueued = fixtures.withPayload(JobKind.RETAIN, "QUEUED",
                "{\"event\": \"sentence.finalized\", \"verdict_id\": \"" + verdictId
                        + "\", \"comment_id\": null, \"version\": 1}");
        UUID otherPostPrepare = fixtures.withPayload(JobKind.PREPARE, "QUEUED", postPayload(otherPost));
        UUID otherVerdictRetry = fixtures.withPayload(JobKind.TEXT_RETRY, "QUEUED", verdictPayload(otherVerdict));
        UUID succeededPrepare = fixtures.withPayload(JobKind.PREPARE, "SUCCEEDED", postPayload(postId));
        UUID failedRetry = fixtures.withPayload(JobKind.TEXT_RETRY, "FAILED", verdictPayload(verdictId));
        UUID sentenceQueued = fixtures.withPayload(JobKind.SENTENCE, "QUEUED", postPayload(postId));
        UUID otherPostSentence = fixtures.withPayload(JobKind.SENTENCE, "RUNNING", postPayload(otherPost));
        UUID alreadyCancelled = fixtures.withPayload(JobKind.PREPARE, "CANCELLED", postPayload(postId));

        List<UUID> cancelled = queries.cancelActiveForPost(postId, List.of(verdictId));

        assertThat(cancelled).containsExactlyInAnyOrder(
                prepareQueued, sentenceRunning, sentenceQueued, textRetryRunning, textRetryQueued);
        assertThat(fixtures.status(otherPostSentence)).isEqualTo("RUNNING");
        for (UUID id : cancelled) {
            JobRow row = queries.findJob(id).orElseThrow();
            assertThat(row.status()).isEqualTo("CANCELLED");
            assertThat(row.ownerId()).isNull();
            assertThat(row.generationId()).isNull();
            assertThat(row.leaseUntil()).isNull();
        }
        assertThat(fixtures.status(retainQueued)).isEqualTo("QUEUED");
        assertThat(fixtures.status(otherPostPrepare)).isEqualTo("QUEUED");
        assertThat(fixtures.status(otherVerdictRetry)).isEqualTo("QUEUED");
        assertThat(fixtures.status(succeededPrepare)).isEqualTo("SUCCEEDED");
        assertThat(fixtures.status(failedRetry)).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("10 §8 D-26 cancelActiveForPost — verdict 가 없으면(빈 목록) PREPARE·SENTENCE 만")
    void cancelActiveForPostWithoutVerdicts() {
        String postId = UUID.randomUUID().toString();
        UUID prepare = fixtures.withPayload(JobKind.PREPARE, "RUNNING", postPayload(postId));

        assertThat(queries.cancelActiveForPost(postId, List.of())).containsExactly(prepare);
    }

    @Test
    @DisplayName("10 §6 4단계 cancel — 진행 중 job 은 CANCELLED, 끝난 job 은 그대로")
    void cancelJob() {
        UUID running = fixtures.withPayload(JobKind.SENTENCE, "RUNNING", "{}");
        UUID succeeded = fixtures.withPayload(JobKind.SENTENCE, "SUCCEEDED", "{}");

        assertThat(queries.cancel(running)).isTrue();
        JobRow row = queries.findJob(running).orElseThrow();
        assertThat(row.status()).isEqualTo("CANCELLED");
        assertThat(row.ownerId()).isNull();
        assertThat(row.generationId()).isNull();
        assertThat(row.leaseUntil()).isNull();

        assertThat(queries.cancel(succeeded)).isFalse();
        assertThat(fixtures.status(succeeded)).isEqualTo("SUCCEEDED");
    }

    @Test
    @DisplayName("findJob — 없는 id 는 빈 결과")
    void findJobMissing() {
        assertThat(queries.findJob(UUID.randomUUID())).isEmpty();
    }
}
