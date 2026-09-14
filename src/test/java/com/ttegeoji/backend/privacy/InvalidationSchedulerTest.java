package com.ttegeoji.backend.privacy;

import com.ttegeoji.backend.privacy.InvalidationServiceTest.AiCase;
import com.ttegeoji.backend.privacy.InvalidationServiceTest.Fixtures;
import com.ttegeoji.backend.support.PostgresContainerSupport;
import com.ttegeoji.backend.util.Json;
import com.ttegeoji.backend.verdict.TemplateCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

// 실제로 커밋한다. 테스트 JVM 에는 컨텍스트가 여럿이라 다른 컨텍스트의 @Scheduled 실행이 같은 PENDING 행을 먼저 집을 수 있다.
// 그래서 결과는 "누가 처리했든 같은" 최종 상태로 보고(processUntil), 실패 유도는 빈 스파이가 아니라 이 테스트 데이터에만 걸리는 트리거로 한다
@SpringBootTest
@ExtendWith(OutputCaptureExtension.class)
class InvalidationSchedulerTest extends PostgresContainerSupport {

    private static final Pattern LAST_ERROR = Pattern.compile("^[\\w.$]+( sqlstate=[0-9A-Z]{5})?$");
    private static final Pattern BIND = Pattern.compile("(?<![:\\w]):([a-z_]+)");

    @Autowired
    private InvalidationScheduler scheduler;
    @Autowired
    private InvalidationQueries queries;
    @Autowired
    private InvalidationService service;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TransactionTemplate tx;
    @Autowired
    private TemplateCatalog templateCatalog;

    private Fixtures f;

    @BeforeEach
    void setUp() {
        f = new Fixtures(jdbc);
    }

    @Test
    @DisplayName("10 §16.3 invalidate_scope.sql 분할 — 주석 제거 뒤 5문장, 바인드 {t,id}·{}·{}·{t,id}·{scope_key}")
    void splitStatements() {
        List<String> statements = InvalidationQueries.splitStatements(InvalidationQueries.loadInvalidateScopeSql());

        assertThat(statements).hasSize(5);
        assertThat(statements.getFirst()).startsWith("WITH hit AS");
        assertThat(statements).noneMatch(s -> s.contains("--") || s.contains("?"));
        assertThat(statements.stream().map(InvalidationSchedulerTest::bindNames).toList())
                .containsExactly(Set.of("t", "id"), Set.of(), Set.of(), Set.of("t", "id"), Set.of("scope_key"));
        assertThat(queries.invalidateStatements()).isEqualTo(statements);
    }

    @Test
    @DisplayName("10 §8 9/14 코드 대조 PENDING 처리 → 5문장이 evidence·dossiers·trial_prep INVALIDATED·memory_facts·node_results 표시, 다른 post 그대로")
    void invalidatesDerivedRows() {
        String post = UUID.randomUUID().toString();
        String other = UUID.randomUUID().toString();
        AiCase target = f.aiCase(post, "post:" + post);
        AiCase untouched = f.aiCase(other, "post:" + other);
        long id = f.pendingInvalidation("post:" + post, "POST", post);

        processUntilDone(id);

        Map<String, Object> state = f.aiState(target);
        assertThat(state.get("evidence")).isNotNull();
        assertThat(state.get("dossier")).isNotNull();
        assertThat((String) state.get("trial_prep")).startsWith("INVALIDATED ").doesNotEndWith(" -");
        assertThat(state.get("memory_fact")).isNotNull();
        assertThat(state.get("node_result")).isNotNull();
        assertThat(f.aiState(untouched))
                .containsEntry("evidence", null)
                .containsEntry("dossier", null)
                .containsEntry("trial_prep", "DOSSIER_READY -")
                .containsEntry("memory_fact", null)
                .containsEntry("node_result", null);
    }

    @Test
    @DisplayName("10 §8 9/14 코드 대조 node_results privacy_versions [{scope_key, epoch}] 는 @> 로 매치, ?(jsonb_exists)로는 0행")
    void nodeResultsMatchByContainment() {
        String post = UUID.randomUUID().toString();
        String scopeKey = "post:" + post;
        AiCase c = f.aiCase(post, scopeKey);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai.node_results WHERE call_id = ? AND jsonb_exists(privacy_versions, ?)",
                Integer.class, c.call(), scopeKey)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM ai.node_results
                 WHERE call_id = ? AND privacy_versions @> jsonb_build_array(jsonb_build_object('scope_key', CAST(? AS text)))""",
                Integer.class, c.call(), scopeKey)).isEqualTo(1);

        // 출처가 다른 (t, id) 로 기록돼도 scope_key 만으로 node_results 가 표시된다
        long id = f.pendingInvalidation(scopeKey, "COMMENT", UUID.randomUUID().toString());
        processUntilDone(id);

        assertThat(f.aiState(c).get("node_result")).isNotNull();
        assertThat(f.aiState(c).get("evidence")).isNull();
    }

    @Test
    @DisplayName("10 §8 4문단 무효 evidence 를 현재 text_version 으로 인용한 강도만 TEMPLATE 전환, 형량 FINAL·양형 이유 유지, 재실행 멱등")
    void convertsAffectedTextsToTemplate() {
        UUID author = f.profile();
        UUID post = f.post(author);
        UUID otherPost = f.post(author);
        UUID verdict = f.verdict(post);
        UUID otherVerdict = f.verdict(otherPost);
        jdbc.update("UPDATE verdicts SET sentencing_reason = 'AI 양형 이유', reason_source = 'AI' WHERE id = ?", verdict);
        AiCase target = f.aiCase(post.toString(), ScopeKeys.post(post));
        AiCase other = f.aiCase(otherPost.toString(), ScopeKeys.post(otherPost));
        f.verdictText(verdict, "mild", 2);
        f.verdictText(verdict, "spicy", 2);
        f.verdictText(otherVerdict, "mild", 2);
        jdbc.update("""
                UPDATE verdict_texts SET dossier_id = ?, privacy_epoch_snapshot = '[{"scope_key": "x", "epoch": 1}]'::jsonb
                 WHERE verdict_id = ?""", target.dossier(), verdict);
        f.ref(verdict, 2, "mild", target.evidence());
        // 옛 text_version 의 인용은 현재 문구와 무관하다
        f.ref(verdict, 1, "spicy", target.evidence());
        f.ref(otherVerdict, 2, "mild", other.evidence());
        // 기록 행을 커밋하기 전에 본다. 커밋 뒤에는 다른 컨텍스트의 스케줄러가 먼저 처리할 수 있다
        assertThat(queries.findAffectedVerdictTexts("POST", post.toString())).as("무효화 전에는 없음").isEmpty();
        long id = f.pendingInvalidation(ScopeKeys.post(post), "POST", post.toString());

        processUntilDone(id);

        TemplateCatalog.Rendered rendered = templateCatalog.render("guilty", 0, 0, "oneDay");
        Map<String, Object> mild = f.verdictTextRow(verdict, "mild");
        assertThat(mild)
                .containsEntry("source", "TEMPLATE")
                .containsEntry("headline", rendered.headline())
                .containsEntry("text_version", 3L)
                .containsEntry("dossier_id", null)
                .containsEntry("privacy_epoch_snapshot", null);
        assertThat(Json.read((String) mild.get("statement"))).isEqualTo(rendered.statement().stream()
                .map(text -> Map.of("text", text, "kind", "opinion", "evidence_labels", List.of())).toList());
        assertThat(f.verdictTextRow(verdict, "spicy"))
                .containsEntry("source", "AI").containsEntry("headline", "AI 헤드라인").containsEntry("text_version", 2L);
        assertThat(f.verdictTextRow(otherVerdict, "mild")).containsEntry("source", "AI").containsEntry("text_version", 2L);
        assertThat(jdbc.queryForMap("""
                SELECT sentence::text AS sentence, sentence_status, sentence_source, sentencing_reason, reason_source,
                       text_version, text_status, retry_round, pending_retry_at FROM verdicts WHERE id = ?""", verdict))
                .containsEntry("sentence", "oneDay")
                .containsEntry("sentence_status", "FINAL")
                .containsEntry("sentence_source", "RULE")
                .containsEntry("sentencing_reason", "AI 양형 이유")
                .containsEntry("reason_source", "AI")
                .containsEntry("text_version", 3L)
                .containsEntry("text_status", "TEMPLATE_READY")
                .containsEntry("retry_round", 0)
                .containsEntry("pending_retry_at", null);
        assertThat(jdbc.queryForMap("SELECT text_version, text_status FROM verdicts WHERE id = ?", otherVerdict))
                .containsEntry("text_version", 2L).containsEntry("text_status", "AI_READY");
        assertThat(queries.findAffectedVerdictTexts("POST", post.toString())).as("전환 뒤에는 다시 잡히지 않음").isEmpty();

        jdbc.update("UPDATE privacy_invalidations SET status = 'PENDING', processed_at = NULL WHERE id = ?", id);
        processUntilDone(id);

        assertThat(f.verdictTextRow(verdict, "mild")).isEqualTo(mild);
        assertThat(jdbc.queryForObject("SELECT text_version FROM verdicts WHERE id = ?", Long.class, verdict)).isEqualTo(3L);
    }

    @Test
    @DisplayName("10 §8 같은 행 재실행(DONE → PENDING) → 표시 시각 변화 없음(멱등)")
    void rerunIsIdempotent() {
        String post = UUID.randomUUID().toString();
        AiCase c = f.aiCase(post, "post:" + post);
        long id = f.pendingInvalidation("post:" + post, "POST", post);
        processUntilDone(id);
        Map<String, Object> first = f.aiState(c);

        jdbc.update("UPDATE privacy_invalidations SET status = 'PENDING', processed_at = NULL WHERE id = ?", id);
        processUntilDone(id);

        assertThat(f.aiState(c)).isEqualTo(first);
    }

    @Test
    @DisplayName("10 §8 스케줄러 실행 → DONE·processed_at, attempts 0")
    void marksDone() {
        String post = UUID.randomUUID().toString();
        f.aiCase(post, "post:" + post);
        long id = f.pendingInvalidation("post:" + post, "POST", post);

        scheduler.runOnce();
        processUntilDone(id);

        assertThat(f.invalidation(id))
                .containsEntry("status", "DONE")
                .containsEntry("attempts", 0)
                .containsEntry("last_error", null);
        assertThat(f.invalidation(id).get("processed_at")).isNotNull();
    }

    @Test
    @DisplayName("10 §8 SQL 실패 → 표시 롤백·attempts +1·last_error 는 클래스·SQLState 만, 5회째 FAILED + WARN")
    void failureCountsAttempts(CapturedOutput output) throws InterruptedException {
        String post = UUID.randomUUID().toString();
        String suffix = post.replace("-", "");
        String secret = "secret-reason-" + suffix;
        AiCase c = f.aiCase(post, "post:" + post);
        // memory_facts 는 5문장 중 네 번째라 앞의 evidence 표시가 롤백되는지도 본다
        jdbc.execute("""
                CREATE FUNCTION public.fail_invalidation_%1$s() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF OLD.source_id = '%2$s' THEN RAISE EXCEPTION '%3$s'; END IF;
                    RETURN NEW;
                END $$""".formatted(suffix, post, secret));
        jdbc.execute("CREATE TRIGGER fail_invalidation_%1$s BEFORE UPDATE ON ai.memory_facts FOR EACH ROW EXECUTE FUNCTION public.fail_invalidation_%1$s()"
                .formatted(suffix));
        try {
            long id = f.pendingInvalidation("post:" + post, "POST", post);

            Map<String, Object> afterFirst = processUntil(id, row -> ((Integer) row.get("attempts")) >= 1);
            String lastError = (String) afterFirst.get("last_error");
            assertThat(lastError).matches(LAST_ERROR).doesNotContain(secret).doesNotContain(post).contains("sqlstate=P0001");
            assertThat(f.aiState(c)).containsEntry("evidence", null).containsEntry("memory_fact", null);

            Map<String, Object> failed = processUntil(id, row -> "FAILED".equals(row.get("status")));
            assertThat(failed).containsEntry("attempts", InvalidationScheduler.MAX_ATTEMPTS).containsEntry("processed_at", null);
            assertThat(scheduler.processOne(id)).isFalse();
            assertThat(f.invalidation(id)).containsEntry("attempts", InvalidationScheduler.MAX_ATTEMPTS);
            assertThat(f.aiState(c)).containsEntry("evidence", null);

            String warn = "privacy invalidation FAILED id=" + id + " scope_key=post:" + post;
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!output.getAll().contains(warn) && System.nanoTime() < until) {
                Thread.sleep(50);
            }
            assertThat(output.getAll()).contains(warn).doesNotContain(secret);
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS fail_invalidation_%1$s ON ai.memory_facts".formatted(suffix));
            jdbc.execute("DROP FUNCTION IF EXISTS public.fail_invalidation_%1$s()".formatted(suffix));
        }
    }

    @Test
    @DisplayName("10 §8 두 트랜잭션이 같은 행을 동시에 집지 않는다(FOR UPDATE SKIP LOCKED)")
    void skipLocked() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicLong held = new AtomicLong();
        try {
            Future<?> holder = pool.submit(() -> {
                // 백그라운드 스케줄러가 먼저 집으면 새 행으로 다시 시도한다
                for (int i = 0; i < 20; i++) {
                    long candidate = f.pendingInvalidation("post:" + UUID.randomUUID(), "POST", UUID.randomUUID().toString());
                    Boolean got = tx.execute(s -> {
                        if (queries.lockPending(candidate).isEmpty()) {
                            return false;
                        }
                        held.set(candidate);
                        locked.countDown();
                        awaitLatch(release);
                        return true;
                    });
                    if (Boolean.TRUE.equals(got)) {
                        return;
                    }
                }
                throw new IllegalStateException("행을 잡지 못했다");
            });
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            long id = held.get();

            List<Long> claimed = tx.execute(s -> queries.claimPendingIds(10_000));
            Optional<InvalidationQueries.PendingInvalidation> relocked = tx.execute(s -> queries.lockPending(id));
            assertThat(claimed).doesNotContain(id);
            assertThat(relocked).isEmpty();
            assertThat(scheduler.processOne(id)).isFalse();
            assertThat(f.invalidation(id)).containsEntry("status", "PENDING").containsEntry("attempts", 0);

            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            processUntilDone(id);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("10 §8 삭제 트랜잭션 롤백 → privacy_invalidations 행 없음, 스케줄러가 집을 것 없음")
    void rolledBackDeleteLeavesNoJob() {
        UUID author = f.profile();
        UUID post = f.post(author);

        tx.executeWithoutResult(s -> {
            service.deletePost(post, author);
            s.setRollbackOnly();
        });

        assertThat(f.invalidations(ScopeKeys.post(post))).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM privacy_invalidations WHERE source_id = ?", Integer.class,
                post.toString())).isZero();
    }

    @Test
    @DisplayName("10 §1 invalidate_scope.sql·템플릿 전환·작업 행 잠금·상태 갱신을 SET LOCAL ROLE backend 로 실행해도 권한 오류 없음")
    void runsAsBackendRole() {
        UUID author = f.profile();
        UUID postId = f.post(author);
        UUID verdict = f.verdict(postId);
        String post = postId.toString();
        AiCase c = f.aiCase(post, "post:" + post);
        f.verdictText(verdict, "mild", 2);
        f.ref(verdict, 2, "mild", c.evidence());
        long id = f.pendingInvalidation("post:other-" + post, "POST", "other-" + post);

        tx.executeWithoutResult(s -> {
            jdbc.execute("SET LOCAL ROLE backend");
            queries.lockPending(id);
            queries.claimPendingIds(1);
            queries.runInvalidateScope("POST", post, "post:" + post);
            scheduler.convertAffectedTextsToTemplate(new InvalidationQueries.PendingInvalidation(id, "post:" + post, "POST", post, 0));
            queries.recordFailure(id, "x", InvalidationScheduler.MAX_ATTEMPTS);
            queries.markDone(id);
        });

        assertThat(f.aiState(c).get("evidence")).isNotNull();
        assertThat(f.aiState(c).get("node_result")).isNotNull();
        assertThat(f.verdictTextRow(verdict, "mild")).containsEntry("source", "TEMPLATE");
    }

    private void processUntilDone(long id) {
        processUntil(id, row -> "DONE".equals(row.get("status")));
    }

    private Map<String, Object> processUntil(long id, java.util.function.Predicate<Map<String, Object>> done) {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < until) {
            scheduler.processOne(id);
            Map<String, Object> row = f.invalidation(id);
            if (done.test(row)) {
                return row;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return fail("무효화 행 " + id + " 이 기대 상태가 되지 않았다: " + f.invalidation(id));
    }

    private static Set<String> bindNames(String statement) {
        Matcher m = BIND.matcher(statement);
        return m.results().map(r -> r.group(1)).collect(Collectors.toSet());
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("release 대기 시간 초과");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
