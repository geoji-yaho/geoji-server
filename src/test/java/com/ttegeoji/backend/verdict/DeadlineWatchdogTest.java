package com.ttegeoji.backend.verdict;

import com.ttegeoji.backend.config.InternalApiException;
import com.ttegeoji.backend.jobs.JobKind;
import com.ttegeoji.backend.jobs.JobQueries;
import com.ttegeoji.backend.privacy.PrivacyEpochRepository;
import com.ttegeoji.backend.privacy.ScopeKeys;
import com.ttegeoji.backend.repository.VerdictRepository;
import com.ttegeoji.backend.support.PostgresContainerSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// test 프로필에는 watchdog 빈이 없다(백그라운드 주기 없음). 인스턴스를 직접 만들어 주기를 부른다.
// 서비스 트랜잭션을 실제로 커밋한다. 사건마다 새 사용자·방·게시물이라 scope key 가 겹치지 않는다
@SpringBootTest
class DeadlineWatchdogTest extends PostgresContainerSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String VALID_LEASE = "now() + interval '30 seconds'";

    @Autowired
    private SchedulerQueries schedulerQueries;
    @Autowired
    private GenerationQueries generationQueries;
    @Autowired
    private PrivacyEpochRepository privacyEpochs;
    @Autowired
    private VerdictRepository verdictRepository;
    @Autowired
    private VerdictFallbackService fallbackService;
    @Autowired
    private JobQueries jobQueries;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private FinalizeService finalizeService;
    @Autowired
    private com.ttegeoji.backend.jobs.JobEnqueuer jobEnqueuer;
    @Autowired
    private GenerationFailedService generationFailedService;
    @Autowired
    private JdbcTemplate jdbc;

    private VerdictFallbackServiceTest.Seed seed;
    private DeadlineWatchdog watchdog;

    @BeforeEach
    void setUp() {
        seed = new VerdictFallbackServiceTest.Seed(jdbc);
        watchdog = newWatchdog();
    }

    private DeadlineWatchdog newWatchdog() {
        return new DeadlineWatchdog(schedulerQueries, generationQueries, privacyEpochs, verdictRepository,
                fallbackService, jobQueries, transactionManager);
    }

    /** SENTENCE 가 begin 해 RUNNING·active 인 PENDING verdict. 마감은 verdict·job 같은 식 */
    private record Running(VerdictFallbackServiceTest.Seed.Case c, UUID jobId, UUID generationId) {
    }

    private Running runningSentence(String deadlineExpr) {
        VerdictFallbackServiceTest.Seed.Case c = seed.pendingVerdict("guilty", 2, 1, deadlineExpr);
        UUID generationId = UUID.randomUUID();
        UUID jobId = seed.runningJob(c.verdictId(), JobKind.SENTENCE, generationId, VALID_LEASE, deadlineExpr);
        seed.updateVerdict(c.verdictId(), "active_job_id = ?, active_generation_id = ?, text_status = 'GENERATING'",
                jobId, generationId);
        return new Running(c, jobId, generationId);
    }

    private Map<String, Object> job(UUID jobId) {
        return jdbc.queryForMap("SELECT status, owner_id, generation_id, lease_until, updated_at FROM ai.jobs WHERE id = ?",
                jobId);
    }

    private boolean dbNowAfterDeadline(UUID verdictId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT now() > deadline_at FROM verdicts WHERE id = ?",
                Boolean.class, verdictId));
    }

    @Test
    @DisplayName("10 §13 작업 8 9초 시점 워커 종료(job RUNNING, 응답 없음) → 마감 뒤 폴백 1회, job CANCELLED, RETAIN 1·round 1")
    void workerDiesAtNineSeconds() throws InterruptedException {
        // 마감 1초 전(10초 중 9초 경과)에 워커가 멈춘 상태. lease 는 아직 유효하다
        Running r = runningSentence("now() + interval '1 second'");
        UUID verdictId = r.c().verdictId();

        assertThat(watchdog.fallback(verdictId, r.c().postId())).isFalse();
        assertThat(seed.verdict(verdictId).get("sentence_status")).isEqualTo("PENDING");
        assertThat(job(r.jobId()).get("status")).isEqualTo("RUNNING");

        // 마감이 지날 때까지 주기를 돌린다(250ms 주기 흉내)
        boolean applied = false;
        for (int i = 0; i < 20 && !applied; i++) {
            Thread.sleep(250);
            applied = watchdog.fallback(verdictId, r.c().postId());
        }
        assertThat(applied).isTrue();
        assertThat(watchdog.fallback(verdictId, r.c().postId())).isFalse();

        Map<String, Object> verdict = seed.verdict(verdictId);
        assertThat(verdict.get("sentence_status")).isEqualTo("FINAL");
        assertThat(verdict.get("sentence")).isEqualTo("oneDay");
        assertThat(verdict.get("sentence_source")).isEqualTo("RULE");
        assertThat(verdict.get("text_status")).isEqualTo("TEMPLATE_READY");
        assertThat(verdict.get("active_job_id")).isNull();
        assertThat(verdict.get("active_generation_id")).isNull();
        assertThat(verdict.get("retry_round")).isEqualTo(1);
        assertThat(((Number) verdict.get("retry_in_seconds")).doubleValue()).isBetween(290.0, 300.0);
        assertThat(seed.texts(verdictId)).hasSize(2).allSatisfy(t -> assertThat(t.get("source")).isEqualTo("TEMPLATE"));
        assertThat(seed.retainCount(verdictId)).isEqualTo(1);

        Map<String, Object> job = job(r.jobId());
        assertThat(job.get("status")).isEqualTo("CANCELLED");
        assertThat(job.get("owner_id")).isNull();
        assertThat(job.get("generation_id")).isNull();
        assertThat(job.get("lease_until")).isNull();
        // 시각 비교: 취소 시각(=폴백 트랜잭션)이 마감 뒤다. 엄밀한 10,000ms 보장은 아니다(10 §6)
        assertThat(Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT j.updated_at >= v.deadline_at FROM ai.jobs j, verdicts v WHERE j.id = ? AND v.id = ?",
                Boolean.class, r.jobId(), verdictId))).isTrue();
    }

    @Test
    @DisplayName("10 §6 runCycle 이 마감 지난 PENDING 을 찾아 폴백하고 begin 전 QUEUED SENTENCE 도 CANCELLED")
    void runCycleFindsOverdueAndCancelsQueued() {
        VerdictFallbackServiceTest.Seed.Case c = seed.pendingVerdict("notGuilty", 1, 2, "now() - interval '1 second'");
        UUID queued = seed.runningJob(c.verdictId(), JobKind.SENTENCE, UUID.randomUUID(), VALID_LEASE,
                "now() - interval '1 second'");
        jdbc.update("UPDATE ai.jobs SET status = 'QUEUED', owner_id = NULL, generation_id = NULL, lease_until = NULL WHERE id = ?",
                queued);

        assertThat(watchdog.runCycle()).isGreaterThanOrEqualTo(1);

        assertThat(seed.verdict(c.verdictId()).get("sentence_status")).isEqualTo("FINAL");
        assertThat(job(queued).get("status")).isEqualTo("CANCELLED");
        assertThat(seed.retainCount(c.verdictId())).isEqualTo(1);
    }

    @Test
    @DisplayName("10 §6 watchdog 두 인스턴스 동시 → FINAL 1회·RETAIN 1·text_version 1")
    void twoInstancesFixOnce() throws Exception {
        Running r = runningSentence("now() - interval '1 second'");
        DeadlineWatchdog other = newWatchdog();
        List<String> scopeKeys = generationQueries.scopeKeys(r.c().postId());
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            // 다른 트랜잭션이 scope 를 잡고 있는 동안 두 인스턴스를 띄워 둘 다 잠금 앞에서 겹치게 한다
            Future<?> holder = pool.submit(() -> tx(() -> {
                privacyEpochs.lockAndRead(scopeKeys);
                holding.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(holding.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Boolean> a = pool.submit(() -> watchdog.fallback(r.c().verdictId(), r.c().postId()));
            Future<Boolean> b = pool.submit(() -> other.fallback(r.c().verdictId(), r.c().postId()));
            Thread.sleep(500);
            assertThat(a.isDone()).isFalse();
            assertThat(b.isDone()).isFalse();
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            assertThat(List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }

        Map<String, Object> verdict = seed.verdict(r.c().verdictId());
        assertThat(verdict.get("sentence_status")).isEqualTo("FINAL");
        assertThat(((Number) verdict.get("text_version")).longValue()).isEqualTo(1L);
        assertThat(seed.retainCount(r.c().verdictId())).isEqualTo(1);
        assertThat(job(r.jobId()).get("status")).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("10 §6 2단계 이미 AI_READY 면 skip — 형량·문구·job 불변, RETAIN 추가 없음")
    void aiReadySkipped() {
        Running r = runningSentence("now() - interval '1 second'");
        seed.updateVerdict(r.c().verdictId(), "text_status = 'AI_READY'");

        assertThat(watchdog.fallback(r.c().verdictId(), r.c().postId())).isFalse();
        watchdog.runCycle();

        Map<String, Object> verdict = seed.verdict(r.c().verdictId());
        assertThat(verdict.get("sentence_status")).isEqualTo("PENDING");
        assertThat(verdict.get("text_status")).isEqualTo("AI_READY");
        assertThat(seed.retainCount(r.c().verdictId())).isZero();
        assertThat(job(r.jobId()).get("status")).isEqualTo("RUNNING");
        seed.finishJobs(r.jobId());
    }

    @Test
    @DisplayName("10 §6 2단계 FINAL + TEMPLATE_READY 면 skip — text_version·retry_round 불변")
    void finalTemplateReadySkipped() {
        VerdictFallbackServiceTest.Seed.Case c = seed.pendingVerdict("guilty", 2, 1, "now() - interval '1 minute'");
        seed.updateVerdict(c.verdictId(), """
                sentence_status = 'FINAL', sentence = CAST('oneDay' AS sentence), sentence_source = 'RULE',
                reason_source = 'TEMPLATE', text_status = 'TEMPLATE_READY', text_version = 1, retry_round = 2
                """);

        assertThat(watchdog.fallback(c.verdictId(), c.postId())).isFalse();
        watchdog.runCycle();

        Map<String, Object> verdict = seed.verdict(c.verdictId());
        assertThat(((Number) verdict.get("text_version")).longValue()).isEqualTo(1L);
        assertThat(verdict.get("retry_round")).isEqualTo(2);
        assertThat(seed.retainCount(c.verdictId())).isZero();
    }

    @Test
    @DisplayName("10 §6 D-24 deadline_at NULL(SENTENCE 보류) 은 대상 아님")
    void heldSentenceNotTarget() {
        VerdictFallbackServiceTest.Seed.Case c = seed.pendingVerdict("guilty", 2, 1, "NULL");

        assertThat(schedulerQueries.overduePendingVerdicts())
                .noneMatch(v -> v.verdictId().equals(c.verdictId()));
        assertThat(watchdog.fallback(c.verdictId(), c.postId())).isFalse();
        watchdog.runCycle();

        Map<String, Object> verdict = seed.verdict(c.verdictId());
        assertThat(verdict.get("sentence_status")).isEqualTo("PENDING");
        assertThat(verdict.get("deadline_at")).isNull();
        assertThat(seed.retainCount(c.verdictId())).isZero();
    }

    @Test
    @DisplayName("10 §13 작업 8·§6 6단계 폴백 뒤 이전 워커 finalize → 409 STALE_GENERATION, RETAIN 여전히 1")
    void lateFinalizeRejectedAfterFallback() {
        Running r = runningSentence("now() - interval '1 second'");
        byte[] late = finalizeBody(r);

        assertThat(watchdog.fallback(r.c().verdictId(), r.c().postId())).isTrue();

        assertThatThrownBy(() -> finalizeService.finalizeVerdict(r.c().verdictId().toString(), late))
                .isInstanceOfSatisfying(InternalApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getCode()).isEqualTo("STALE_GENERATION");
                });
        Map<String, Object> verdict = seed.verdict(r.c().verdictId());
        assertThat(verdict.get("text_status")).isEqualTo("TEMPLATE_READY");
        assertThat(verdict.get("sentence_source")).isEqualTo("RULE");
        assertThat(seed.retainCount(r.c().verdictId())).isEqualTo(1);
    }

    @Test
    @DisplayName("10 §13 작업 3 평결 commit 직후 중단 — 재기동 watchdog 은 마감 전 손대지 않고, 워커 확정 뒤 마감이 지나도 중복 확정 없음")
    void noDuplicateAfterCrashRightAfterCommit() {
        // 평결·SENTENCE job 은 커밋돼 있고 백엔드가 멈췄다가 새 인스턴스로 뜬 상황
        Running r = runningSentence("now() + interval '10 seconds'");
        DeadlineWatchdog restarted = newWatchdog();

        restarted.runCycle();
        assertThat(seed.verdict(r.c().verdictId()).get("sentence_status")).isEqualTo("PENDING");
        assertThat(job(r.jobId()).get("status")).isEqualTo("RUNNING");

        // 워커가 마감 안에 finalize 한 결과(10 §5): FINAL/AI·AI_READY·RETAIN 1·job SUCCEEDED
        seed.updateVerdict(r.c().verdictId(), """
                sentence_status = 'FINAL', sentence = CAST('oneDay' AS sentence), sentence_source = 'AI',
                reason_source = 'AI', text_status = 'AI_READY', text_version = 1,
                active_job_id = NULL, active_generation_id = NULL
                """);
        jdbc.update("UPDATE ai.jobs SET status = 'SUCCEEDED', owner_id = NULL, generation_id = NULL, lease_until = NULL WHERE id = ?",
                r.jobId());
        tx(() -> jobEnqueuer.enqueueRetainVerdict(r.c().verdictId().toString(), 1));
        // 마감이 지난 뒤
        seed.updateVerdict(r.c().verdictId(), "deadline_at = now() - interval '1 second'");
        assertThat(dbNowAfterDeadline(r.c().verdictId())).isTrue();

        assertThat(restarted.fallback(r.c().verdictId(), r.c().postId())).isFalse();
        restarted.runCycle();

        Map<String, Object> verdict = seed.verdict(r.c().verdictId());
        assertThat(verdict.get("sentence_source")).isEqualTo("AI");
        assertThat(verdict.get("text_status")).isEqualTo("AI_READY");
        assertThat(verdict.get("retry_round")).isEqualTo(0);
        assertThat(seed.texts(r.c().verdictId())).isEmpty();
        assertThat(seed.retainCount(r.c().verdictId())).isEqualTo(1);
        assertThat(job(r.jobId()).get("status")).isEqualTo("SUCCEEDED");
    }

    @Test
    @DisplayName("10 §6 6단계 폴백 뒤 이전 워커 generation-failed → 409 STALE_GENERATION, round·RETAIN 불변")
    void lateGenerationFailedRejectedAfterFallback() {
        Running r = runningSentence("now() - interval '1 second'");
        assertThat(watchdog.fallback(r.c().verdictId(), r.c().postId())).isTrue();

        assertThatThrownBy(() -> generationFailedService.fail(r.c().verdictId(),
                new GenerationFailedService.Request(r.jobId(), r.generationId(), "DEADLINE_EXCEEDED"), "trace-late"))
                .isInstanceOfSatisfying(InternalApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getCode()).isEqualTo("STALE_GENERATION");
                });
        Map<String, Object> verdict = seed.verdict(r.c().verdictId());
        assertThat(verdict.get("retry_round")).isEqualTo(1);
        assertThat(seed.retainCount(r.c().verdictId())).isEqualTo(1);
    }

    @Test
    @DisplayName("10 §6 운영 배선 — @Scheduled fixedDelay 250ms, test 프로필 밖에서만 빈")
    void schedulingWiring() throws NoSuchMethodException {
        Scheduled scheduled = DeadlineWatchdog.class.getMethod("runCycle").getAnnotation(Scheduled.class);
        assertThat(scheduled).isNotNull();
        assertThat(scheduled.fixedDelay()).isEqualTo(250L);
        assertThat(DeadlineWatchdog.class.getAnnotation(Profile.class).value()).containsExactly("!test");
        assertThat(DeadlineWatchdog.class.getAnnotation(org.springframework.stereotype.Component.class)).isNotNull();
    }

    private void tx(Runnable action) {
        new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(s -> action.run());
    }

    /** 이전 워커가 마감 전에 만들어 둔 finalize 요청. 모양은 FinalizeServiceTest 와 같다 */
    private byte[] finalizeBody(Running r) {
        ObjectNode draft = (ObjectNode) MAPPER.readTree(FinalizeRequestParserTest.WRITER_DRAFT);
        ObjectNode sentencing = (ObjectNode) MAPPER.readTree(FinalizeRequestParserTest.SENTENCING);
        String hash = DraftHash.sha256Hex(draft, sentencing);
        ObjectNode body = MAPPER.createObjectNode();
        body.put("schema_version", 1);
        body.put("job_id", r.jobId().toString());
        body.put("generation_id", r.generationId().toString());
        body.put("verdict_version", 1);
        body.put("expected_text_version", 0);
        body.put("dossier_id", UUID.randomUUID().toString());
        ArrayNode privacy = body.putArray("privacy_versions");
        UUID authorId = jdbc.queryForObject("SELECT author_id FROM posts WHERE id = ?", UUID.class, r.c().postId());
        List<String> keys = new java.util.ArrayList<>(List.of(ScopeKeys.post(r.c().postId()), ScopeKeys.user(authorId)));
        jdbc.queryForList("SELECT room_id FROM post_rooms WHERE post_id = ?", UUID.class, r.c().postId())
                .forEach(room -> keys.add(ScopeKeys.room(room)));
        privacyEpochs.read(keys).forEach((key, epoch) -> {
            ObjectNode item = privacy.addObject();
            item.put("scope_key", key);
            item.put("epoch", epoch);
        });
        body.put("draft_hash", hash);
        body.set("sentencing", sentencing);
        body.set("draft", draft);
        body.set("evaluation", MAPPER.readTree(FinalizeRequestParserTest.EVALUATION_PASS));
        body.put("evaluation_draft_hash", hash);
        body.put("prompt_bundle_version", "prompts-test");
        body.put("guardrail_policy_version", "guardrail-v2");
        ObjectNode models = body.putObject("model_ids");
        models.put("sentencing", "model-a");
        models.put("writer", "model-b");
        models.put("evaluator", "model-a");
        return MAPPER.writeValueAsBytes(body);
    }
}
