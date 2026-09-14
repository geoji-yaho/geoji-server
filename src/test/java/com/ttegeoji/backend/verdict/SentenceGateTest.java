package com.ttegeoji.backend.verdict;

import com.ttegeoji.backend.jobs.JobEnqueuer;
import com.ttegeoji.backend.support.PostgresContainerSupport;
import com.ttegeoji.backend.verdict.JuryQueries.PendingVerdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// 테스트마다 롤백한다. 커밋하지 않아 백그라운드 JuryScheduler 가 이 verdict 를 보지 못한다(게이트를 테스트가 직접 돌린다)
@SpringBootTest
@Transactional
class SentenceGateTest extends PostgresContainerSupport {

    private static final String VALID_POLICY = """
            {"version": "sentencing-band-v1", "allowed_sentences": [{"code": "probation", "rank": 1}],
             "fallback_sentence": "probation", "reason_required": true}""";

    @Autowired
    private SentenceGate gate;
    @Autowired
    private JuryQueries juryQueries;
    @Autowired
    private JobEnqueuer jobEnqueuer;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("10 §3 D-24 PREPARE 없음 → 즉시 SENTENCE INSERT, job·verdict deadline_at = INSERT 시각 + 10s")
    void noPrepareInsertsImmediately() {
        PendingVerdict verdict = insertVerdict("guilty", VALID_POLICY, "now()");

        assertThat(gate.tryInsert(verdict)).isTrue();

        OffsetDateTime expected = dbNowPlus(10);
        assertThat(sentenceJobCount(verdict)).isEqualTo(1);
        assertThat(sentenceJobDeadline(verdict).toInstant()).isEqualTo(expected.toInstant());
        assertThat(verdictDeadline(verdict).toInstant()).isEqualTo(expected.toInstant());
        assertThat(juryQueries.pendingVerdicts()).doesNotContain(verdict);
    }

    @Test
    @DisplayName("10 §3 D-24 PREPARE RUNNING → 보류, verdict deadline_at NULL·SENTENCE job 0")
    void runningPrepareHolds() {
        PendingVerdict verdict = insertVerdict("guilty", VALID_POLICY, "now()");
        UUID prepare = insertPrepare(verdict.postId());
        setRunning(prepare);

        assertThat(gate.tryInsert(verdict)).isFalse();

        assertThat(verdictDeadline(verdict)).isNull();
        assertThat(sentenceJobCount(verdict)).isZero();
        assertThat(juryQueries.pendingVerdicts()).contains(verdict);
    }

    @Test
    @DisplayName("10 §3 D-24 PREPARE QUEUED → 보류")
    void queuedPrepareHolds() {
        PendingVerdict verdict = insertVerdict("guilty", VALID_POLICY, "now()");
        insertPrepare(verdict.postId());

        assertThat(gate.tryInsert(verdict)).isFalse();
        assertThat(sentenceJobCount(verdict)).isZero();
    }

    @ParameterizedTest(name = "PREPARE {0}")
    @ValueSource(strings = {"SUCCEEDED", "FAILED", "CANCELLED"})
    @DisplayName("10 §3 D-24 보류 뒤 PREPARE 종료 → 다음 주기에 INSERT")
    void prepareFinishedInsertsNextCycle(String finalStatus) {
        PendingVerdict verdict = insertVerdict("guilty", VALID_POLICY, "now()");
        UUID prepare = insertPrepare(verdict.postId());
        setRunning(prepare);
        assertThat(gate.tryInsert(verdict)).isFalse();
        assertThat(sentenceJobCount(verdict)).isZero();

        jdbc.update("""
                UPDATE ai.jobs SET status = ?, owner_id = NULL, generation_id = NULL, lease_until = NULL
                 WHERE id = ?""", finalStatus, prepare);

        // 다음 주기: 스케줄러가 보류 목록에서 찾아 게이트를 다시 돌린다
        assertThat(juryQueries.pendingVerdicts()).contains(verdict);
        assertThat(gate.tryInsert(verdict)).isTrue();
        assertThat(sentenceJobCount(verdict)).isEqualTo(1);
        assertThat(verdictDeadline(verdict).toInstant()).isEqualTo(dbNowPlus(10).toInstant());
        assertThat(juryQueries.pendingVerdicts()).doesNotContain(verdict);
    }

    @Test
    @DisplayName("10 §3 D-24 PREPARE RUNNING 그대로 confirmed_at + 30s 경과 → INSERT, deadline = INSERT 시각 + 10s")
    void waitOverInsertsEvenIfPrepareRunning() {
        PendingVerdict verdict = insertVerdict("guilty", VALID_POLICY, "now() - interval '29 seconds'");
        UUID prepare = insertPrepare(verdict.postId());
        setRunning(prepare);
        assertThat(gate.tryInsert(verdict)).isFalse();

        jdbc.update("UPDATE verdicts SET confirmed_at = now() - interval '30 seconds' WHERE id = ?", verdict.verdictId());

        assertThat(gate.tryInsert(verdict)).isTrue();
        assertThat(sentenceJobCount(verdict)).isEqualTo(1);
        assertThat(verdictDeadline(verdict).toInstant()).isEqualTo(dbNowPlus(10).toInstant());
        assertThat(jdbc.queryForObject("SELECT status FROM ai.jobs WHERE id = ?", String.class, prepare))
                .isEqualTo("RUNNING");
    }

    @Test
    @DisplayName("10 §3 게이트 두 번 → SENTENCE job 1개, 두 번째는 false")
    void secondCallIsNoop() {
        PendingVerdict verdict = insertVerdict("guilty", VALID_POLICY, "now()");

        assertThat(gate.tryInsert(verdict)).isTrue();
        assertThat(gate.tryInsert(verdict)).isFalse();
        assertThat(sentenceJobCount(verdict)).isEqualTo(1);
    }

    @Test
    @DisplayName("10 §3 dismissed verdict → 게이트가 넣지 않고 보류 목록에도 없다")
    void dismissedNeverInserted() {
        PendingVerdict verdict = insertVerdict("dismissed", "null", "now() - interval '1 minute'");

        assertThat(juryQueries.pendingVerdicts()).doesNotContain(verdict);
        assertThat(gate.tryInsert(verdict)).isFalse();
        assertThat(sentenceJobCount(verdict)).isZero();
    }

    @Test
    @DisplayName("10 §3 유죄인데 fallback ∉ 허용 목록 → 게이트가 넣지 않고 보류 목록에도 없다")
    void invalidPolicyNeverInserted() {
        PendingVerdict verdict = insertVerdict("guilty", """
                {"version": "sentencing-band-v1", "allowed_sentences": [{"code": "probation", "rank": 1}],
                 "fallback_sentence": "life", "reason_required": true}""", "now() - interval '1 minute'");

        assertThat(juryQueries.pendingVerdicts()).doesNotContain(verdict);
        assertThat(gate.tryInsert(verdict)).isFalse();
        assertThat(sentenceJobCount(verdict)).isZero();
    }

    @Test
    @DisplayName("10 §3 notGuilty·disagree verdict(policy null) → 게이트가 넣는다")
    void nonGuiltyWithNullPolicyInserted() {
        PendingVerdict notGuilty = insertVerdict("notGuilty", "null", "now()");
        PendingVerdict disagree = insertVerdict("disagree", "null", "now()");

        assertThat(juryQueries.pendingVerdicts()).contains(notGuilty, disagree);
        assertThat(gate.tryInsert(notGuilty)).isTrue();
        assertThat(gate.tryInsert(disagree)).isTrue();
        assertThat(sentenceJobCount(notGuilty)).isEqualTo(1);
        assertThat(sentenceJobCount(disagree)).isEqualTo(1);
    }

    private PendingVerdict insertVerdict(String result, String policyJson, String confirmedAtExpr) {
        UUID author = UUID.randomUUID();
        jdbc.update("INSERT INTO profiles (id, nickname, monthly_budget) VALUES (?, 'author', 0)", author);
        String postType = "agree".equals(result) || "disagree".equals(result) ? "considering" : "spent";
        UUID postId = jdbc.queryForObject("""
                INSERT INTO posts (author_id, post_type, amount_krw, category, item, intake_status, intake_source,
                                   vote_deadline_at)
                VALUES (?, CAST(? AS post_type), 5000, '카페/간식', '라떼', 'PASS', 'AI', now() + interval '1 hour')
                RETURNING id""", UUID.class, author, postType);
        UUID verdictId = jdbc.queryForObject("""
                INSERT INTO verdicts (post_id, jury_result, policy_snapshot, confirmed_at, target_intensities,
                                      default_intensity, applied_intensity)
                VALUES (?, CAST(? AS verdict), CAST(? AS jsonb), %s, '["mild"]'::jsonb, 'mild', 'mild')
                RETURNING id""".formatted(confirmedAtExpr), UUID.class, postId, result, policyJson);
        return new PendingVerdict(verdictId, postId, 1);
    }

    private UUID insertPrepare(UUID postId) {
        return jobEnqueuer.enqueuePrepare(postId.toString(), 1, 1).id();
    }

    private void setRunning(UUID jobId) {
        jdbc.update("""
                UPDATE ai.jobs SET status = 'RUNNING', attempts = 1, owner_id = 'worker-1',
                       generation_id = gen_random_uuid(), lease_until = now() + interval '1 minute'
                 WHERE id = ?""", jobId);
    }

    private int sentenceJobCount(PendingVerdict verdict) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM ai.jobs WHERE kind = 'SENTENCE' AND payload->>'post_id' = ?",
                Integer.class, verdict.postId().toString());
        return count == null ? 0 : count;
    }

    private OffsetDateTime sentenceJobDeadline(PendingVerdict verdict) {
        return jdbc.queryForObject("SELECT deadline_at FROM ai.jobs WHERE dedupe_key = ?", OffsetDateTime.class,
                "sentence:" + verdict.verdictId() + ":1");
    }

    private OffsetDateTime verdictDeadline(PendingVerdict verdict) {
        return jdbc.queryForObject("SELECT deadline_at FROM verdicts WHERE id = ?", OffsetDateTime.class,
                verdict.verdictId());
    }

    private OffsetDateTime dbNowPlus(int seconds) {
        return jdbc.queryForObject("SELECT now() + make_interval(secs => ?)", OffsetDateTime.class, seconds);
    }
}
