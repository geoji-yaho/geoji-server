package com.ttegeoji.backend.jobs;

import com.ttegeoji.backend.support.PostgresContainerSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// reap() 을 테스트 트랜잭션 안에서 직접 부른다. 넣은 행은 커밋되지 않아 백그라운드 스케줄 실행은 보지 못한다.
@SpringBootTest
@Transactional
@ExtendWith(OutputCaptureExtension.class)
class ReaperSchedulerTest extends PostgresContainerSupport {

    @Autowired
    private ReaperScheduler reaper;
    @Autowired
    private JobQueries queries;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private JobRowFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new JobRowFixtures(jdbcTemplate);
    }

    @Test
    @DisplayName("10 §7 reaper CASE 네 갈래 — TEXT_RETRY→FAILED, 마감 지난 SENTENCE→CANCELLED, attempts 소진→FAILED, 그 외 QUEUED")
    void caseBranches(CapturedOutput output) {
        UUID textRetry = fixtures.expiredRunning(JobKind.TEXT_RETRY, 0, 1, "now() + interval '10 seconds'", null);
        UUID sentencePastDeadline = fixtures.expiredRunning(JobKind.SENTENCE, 1, 2, "now() - interval '1 second'", null);
        UUID sentenceNoDeadline = fixtures.expiredRunning(JobKind.SENTENCE, 1, 2, null, null);
        UUID prepareExhausted = fixtures.expiredRunning(JobKind.PREPARE, 2, 2, null, null);
        UUID retainRetry = fixtures.expiredRunning(JobKind.RETAIN, 1, 5, null, null);
        UUID sentenceBeforeDeadline = fixtures.expiredRunning(JobKind.SENTENCE, 1, 2, "now() + interval '5 seconds'", null);
        UUID sentenceExhausted = fixtures.expiredRunning(JobKind.SENTENCE, 2, 2, "now() + interval '5 seconds'", null);
        UUID stillLeased = fixtures.insert(new JobRowFixtures.Spec(JobKind.PREPARE, "RUNNING", "{}", 1, 2, null,
                "now() + interval '30 seconds'", null, null));

        var reaped = reaper.reap();

        assertThat(fixtures.status(textRetry)).isEqualTo("FAILED");
        assertThat(fixtures.status(sentencePastDeadline)).isEqualTo("CANCELLED");
        assertThat(fixtures.status(sentenceNoDeadline)).isEqualTo("CANCELLED");
        assertThat(fixtures.status(prepareExhausted)).isEqualTo("FAILED");
        assertThat(fixtures.status(retainRetry)).isEqualTo("QUEUED");
        assertThat(fixtures.status(sentenceBeforeDeadline)).isEqualTo("QUEUED");
        assertThat(fixtures.status(sentenceExhausted)).isEqualTo("FAILED");
        assertThat(fixtures.status(stillLeased)).isEqualTo("RUNNING");

        for (UUID id : new UUID[]{textRetry, sentencePastDeadline, sentenceNoDeadline, prepareExhausted, retainRetry,
                sentenceBeforeDeadline, sentenceExhausted}) {
            JobRow row = queries.findJob(id).orElseThrow();
            assertThat(row.ownerId()).isNull();
            assertThat(row.generationId()).isNull();
            assertThat(row.leaseUntil()).isNull();
            assertThat(row.lastErrorCode()).isEqualTo("LEASE_EXPIRED");
        }

        // 10 §14 lease_expired_total 답: kind·결과 상태별 건수
        // 테스트 트랜잭션 밖에서 커밋된 만료 행은 없으므로 정확히 이 묶음뿐이다
        assertThat(reaped).containsExactlyInAnyOrder(
                new ReaperScheduler.Reaped("TEXT_RETRY", "FAILED", 1),
                new ReaperScheduler.Reaped("SENTENCE", "CANCELLED", 2),
                new ReaperScheduler.Reaped("SENTENCE", "QUEUED", 1),
                new ReaperScheduler.Reaped("SENTENCE", "FAILED", 1),
                new ReaperScheduler.Reaped("PREPARE", "FAILED", 1),
                new ReaperScheduler.Reaped("RETAIN", "QUEUED", 1));
        for (ReaperScheduler.Reaped r : reaped) {
            assertThat(output).contains("lease_expired_total kind=" + r.kind() + " status=" + r.status()
                    + " count=" + r.count());
        }
    }

    @Test
    @DisplayName("10 §7 reaper — last_error_code 기존 값은 유지")
    void keepsExistingErrorCode() {
        UUID job = fixtures.expiredRunning(JobKind.RETAIN, 1, 5, null, "VENDOR_UNAVAILABLE");

        reaper.reap();

        assertThat(queries.findJob(job).orElseThrow().lastErrorCode()).isEqualTo("VENDOR_UNAVAILABLE");
    }

    @Test
    @DisplayName("10 §7 reaper — 회수 0 건이면 로그 없음")
    void noLogWhenNothingReaped(CapturedOutput output) {
        assertThat(reaper.reap()).isEmpty();
        assertThat(output).doesNotContain("lease_expired_total");
    }
}
