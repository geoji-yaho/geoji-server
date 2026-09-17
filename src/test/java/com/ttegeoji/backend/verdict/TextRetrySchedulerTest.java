package com.ttegeoji.backend.verdict;

import com.ttegeoji.backend.jobs.JobEnqueuer;
import com.ttegeoji.backend.jobs.JobKind;
import com.ttegeoji.backend.jobs.ReaperScheduler;
import com.ttegeoji.backend.support.PostgresContainerSupport;
import com.ttegeoji.backend.util.Json;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// test 프로필에는 스케줄러 빈이 없다. 인스턴스를 직접 만들어 스캔을 부른다. 행은 커밋되고 사건마다 새 id 다
@SpringBootTest
@ExtendWith(OutputCaptureExtension.class)
class TextRetrySchedulerTest extends PostgresContainerSupport {

    @Autowired
    private SchedulerQueries schedulerQueries;
    @Autowired
    private JobEnqueuer jobEnqueuer;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private ReaperScheduler reaper;
    @Autowired
    private JdbcTemplate jdbc;

    private VerdictFallbackServiceTest.Seed seed;
    private TextRetryScheduler scheduler;

    @BeforeEach
    void setUp() {
        seed = new VerdictFallbackServiceTest.Seed(jdbc);
        scheduler = new TextRetryScheduler(schedulerQueries, jobEnqueuer, transactionManager);
    }

    /** 템플릿 노출 중 FINAL verdict. mild 는 TEMPLATE, hell 은 AI 문구. round·예약 시각은 SQL 식 */
    private UUID templateReady(int retryRound, String pendingRetryAtExpr, String textStatus) {
        UUID verdictId = seed.pendingVerdict("guilty", 2, 1, "NULL").verdictId();
        seed.updateVerdict(verdictId, """
                sentence_status = 'FINAL', sentence = CAST('oneDay' AS sentence), sentence_source = 'RULE',
                reason_source = 'TEMPLATE', text_status = ?, text_version = 1, retry_round = ?,
                pending_retry_at = %s
                """.formatted(pendingRetryAtExpr), textStatus, retryRound);
        for (String[] row : List.of(new String[]{"mild", "TEMPLATE"}, new String[]{"hell", "AI"})) {
            jdbc.update("""
                    INSERT INTO verdict_texts (verdict_id, intensity, headline, statement, source, text_version)
                    VALUES (?, CAST(? AS spice_level), '유죄', CAST('[{"text":"t","kind":"opinion","evidence_labels":[]}]' AS jsonb),
                            ?, 1)
                    """, verdictId, row[0], row[1]);
        }
        return verdictId;
    }

    private List<Map<String, Object>> textRetryJobs(UUID verdictId) {
        return jdbc.queryForList("""
                SELECT id, dedupe_key, status, payload::text AS payload,
                       EXTRACT(EPOCH FROM deadline_at - created_at) AS deadline_after_seconds
                  FROM ai.jobs WHERE kind = 'TEXT_RETRY' AND aggregate_id = ?
                """, verdictId.toString());
    }

    /** 워커가 begin-generation 까지 한 뒤 lease 가 만료된 TEXT_RETRY. verdict active 로 건다 */
    private UUID expiredRunningRetry(UUID verdictId, int round) {
        UUID jobId = UUID.randomUUID();
        UUID generationId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO ai.jobs (id, event_id, event_type, kind, dedupe_key, aggregate_id, aggregate_version, payload,
                                     status, priority, attempts, max_attempts, deadline_at, lease_until, owner_id,
                                     generation_id, trace_id)
                VALUES (?, gen_random_uuid(), 'verdict.text_retry', 'TEXT_RETRY', ?, ?, 1, CAST(? AS jsonb), 'RUNNING',
                        50, 1, 1, now() - interval '1 minute', now() - interval '1 second', 'worker-1', ?, 'trace')
                """, jobId, "text-retry:" + verdictId + ":1:" + round, verdictId.toString(),
                Json.write(Map.of("verdict_id", verdictId.toString(), "verdict_version", 1, "round", round)),
                generationId);
        seed.updateVerdict(verdictId, "active_job_id = ?, active_generation_id = ?", jobId, generationId);
        return jobId;
    }

    private String jobStatusAndCode(UUID jobId) {
        return jdbc.queryForObject("SELECT status || ':' || last_error_code FROM ai.jobs WHERE id = ?", String.class, jobId);
    }

    @Test
    @DisplayName("10 §7 round 1 pending_retry_at 지남 → TEXT_RETRY 1(dedupe text-retry:{v}:{ver}:1·deadline = TEXT_RETRY 마감·intensities = TEMPLATE 강도), 예약 비움")
    void dueRoundOneEnqueued() {
        UUID verdictId = templateReady(1, "now() - interval '1 second'", "TEMPLATE_READY");

        assertThat(scheduler.enqueue(verdictId)).isTrue();

        List<Map<String, Object>> jobs = textRetryJobs(verdictId);
        assertThat(jobs).hasSize(1);
        Map<String, Object> job = jobs.getFirst();
        assertThat(job.get("dedupe_key")).isEqualTo("text-retry:" + verdictId + ":1:1");
        assertThat(job.get("status")).isEqualTo("QUEUED");
        double expected = JobKind.TEXT_RETRY.deadlineAfterSeconds();
        assertThat(((Number) job.get("deadline_after_seconds")).doubleValue())
                .isBetween(expected - 1, expected + 1);
        assertThat(Json.read((String) job.get("payload"))).isEqualTo(Map.of(
                "verdict_id", verdictId.toString(), "verdict_version", 1, "round", 1, "intensities", List.of("mild")));
        Map<String, Object> verdict = seed.verdict(verdictId);
        assertThat(verdict.get("pending_retry_at")).isNull();
        assertThat(verdict.get("retry_round")).isEqualTo(1);
    }

    @Test
    @DisplayName("10 §7 enqueueDue 스캔이 예약 지난 verdict 를 찾는다")
    void scanFindsDue() {
        UUID verdictId = templateReady(1, "now() - interval '1 second'", "TEMPLATE_READY");

        assertThat(scheduler.enqueueDue()).isGreaterThanOrEqualTo(1);

        assertThat(textRetryJobs(verdictId)).hasSize(1);
    }

    @Test
    @DisplayName("10 §7 예약 시각이 아직 안 지남 → job 없음, 예약 유지")
    void notYetDue() {
        UUID verdictId = templateReady(1, "now() + interval '5 minutes'", "TEMPLATE_READY");

        assertThat(schedulerQueries.dueRetryVerdictIds()).doesNotContain(verdictId);
        assertThat(scheduler.enqueue(verdictId)).isFalse();
        scheduler.runCycle();

        assertThat(textRetryJobs(verdictId)).isEmpty();
        assertThat(seed.verdict(verdictId).get("pending_retry_at")).isNotNull();
    }

    @Test
    @DisplayName("10 §7 두 번 스캔해도 TEXT_RETRY 1")
    void twoScansOneJob() {
        UUID verdictId = templateReady(1, "now() - interval '1 second'", "TEMPLATE_READY");

        scheduler.runCycle();
        scheduler.runCycle();
        assertThat(scheduler.enqueue(verdictId)).isFalse();

        assertThat(textRetryJobs(verdictId)).hasSize(1);
    }

    @Test
    @DisplayName("10 §7 AI_READY 면 재시도 대상 아님")
    void aiReadyNotTarget() {
        UUID verdictId = templateReady(1, "now() - interval '1 second'", "AI_READY");

        assertThat(schedulerQueries.dueRetryVerdictIds()).doesNotContain(verdictId);
        scheduler.runCycle();

        assertThat(textRetryJobs(verdictId)).isEmpty();
    }

    @Test
    @DisplayName("10 §7 reaper 가 round 1 TEXT_RETRY 를 FAILED(LEASE_EXPIRED) → round 2 예약 +10분, active 비움, 다시 돌려도 그대로")
    void reapedRoundOneSchedulesRoundTwo() {
        UUID verdictId = templateReady(1, "NULL", "TEMPLATE_READY");
        UUID jobId = expiredRunningRetry(verdictId, 1);
        reaper.reap();
        assertThat(jobStatusAndCode(jobId)).isEqualTo("FAILED:LEASE_EXPIRED");

        assertThat(scheduler.handleReaped(verdictId)).isTrue();

        Map<String, Object> verdict = seed.verdict(verdictId);
        assertThat(verdict.get("retry_round")).isEqualTo(2);
        assertThat(((Number) verdict.get("retry_in_seconds")).doubleValue()).isBetween(590.0, 600.0);
        assertThat(verdict.get("active_job_id")).isNull();
        assertThat(verdict.get("active_generation_id")).isNull();
        Object pendingRetryAt = verdict.get("pending_retry_at");

        scheduler.runCycle();
        assertThat(scheduler.handleReaped(verdictId)).isFalse();
        assertThat(seed.verdict(verdictId).get("retry_round")).isEqualTo(2);
        assertThat(seed.verdict(verdictId).get("pending_retry_at")).isEqualTo(pendingRetryAt);
        assertThat(textRetryJobs(verdictId)).hasSize(1);
    }

    @Test
    @DisplayName("10 §7 begin 전에 회수된 round 1(active 없음)도 round 2 예약")
    void reapedBeforeBeginSchedulesRoundTwo() {
        UUID verdictId = templateReady(1, "NULL", "TEMPLATE_READY");
        UUID jobId = expiredRunningRetry(verdictId, 1);
        seed.updateVerdict(verdictId, "active_job_id = NULL, active_generation_id = NULL");
        reaper.reap();
        assertThat(jobStatusAndCode(jobId)).isEqualTo("FAILED:LEASE_EXPIRED");

        assertThat(schedulerQueries.reapedRetryVerdictIds()).contains(verdictId);
        scheduler.scheduleAfterReap();

        assertThat(seed.verdict(verdictId).get("retry_round")).isEqualTo(2);
    }

    @Test
    @DisplayName("10 §7 round 3 TEXT_RETRY 회수 → 예약 없음 + WARN 운영 알림, 한 번만")
    void reapedLastRoundWarns(CapturedOutput output) {
        UUID verdictId = templateReady(3, "NULL", "TEMPLATE_READY");
        UUID jobId = expiredRunningRetry(verdictId, 3);
        reaper.reap();
        assertThat(jobStatusAndCode(jobId)).isEqualTo("FAILED:LEASE_EXPIRED");

        assertThat(scheduler.handleReaped(verdictId)).isTrue();
        assertThat(scheduler.handleReaped(verdictId)).isFalse();

        Map<String, Object> verdict = seed.verdict(verdictId);
        assertThat(verdict.get("retry_round")).isEqualTo(3);
        assertThat(verdict.get("pending_retry_at")).isNull();
        assertThat(verdict.get("active_job_id")).isNull();
        assertThat(verdict.get("text_status")).isEqualTo("TEMPLATE_READY");
        assertThat(output.getAll().lines()
                .filter(line -> line.contains("WARN") && line.contains("운영 알림") && line.contains(verdictId.toString())))
                .hasSize(1);
    }

    @Test
    @DisplayName("10 §7 운영 배선 — 스캔 주기 5분(fixedDelay 300000ms), test 프로필 밖에서만 빈")
    void schedulingWiring() throws NoSuchMethodException {
        org.springframework.scheduling.annotation.Scheduled scheduled = TextRetryScheduler.class.getMethod("runCycle")
                .getAnnotation(org.springframework.scheduling.annotation.Scheduled.class);
        assertThat(scheduled).isNotNull();
        assertThat(scheduled.fixedDelay()).isEqualTo(300_000L);
        assertThat(TextRetryScheduler.class.getAnnotation(org.springframework.context.annotation.Profile.class).value())
                .containsExactly("!test");
    }

    @Test
    @DisplayName("10 §4.6·§7 generation-failed 가 이미 다음 round 를 잡은 뒤 회수된 job 은 다시 예약하지 않는다")
    void reapedAfterGenerationFailedIgnored() {
        UUID verdictId = templateReady(1, "NULL", "TEMPLATE_READY");
        UUID jobId = expiredRunningRetry(verdictId, 1);
        // generation-failed(10 §4.6 표 3행)가 한 일: round 2 예약·active 비움
        seed.updateVerdict(verdictId,
                "retry_round = 2, pending_retry_at = now() + interval '10 minutes', active_job_id = NULL, active_generation_id = NULL");
        reaper.reap();
        assertThat(jobStatusAndCode(jobId)).isEqualTo("FAILED:LEASE_EXPIRED");

        assertThat(scheduler.handleReaped(verdictId)).isFalse();

        assertThat(seed.verdict(verdictId).get("retry_round")).isEqualTo(2);
    }
}
