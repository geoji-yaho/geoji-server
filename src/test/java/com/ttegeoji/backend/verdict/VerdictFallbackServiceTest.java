package com.ttegeoji.backend.verdict;

import com.ttegeoji.backend.domain.Verdict;
import com.ttegeoji.backend.jobs.JobKind;
import com.ttegeoji.backend.repository.VerdictRepository;
import com.ttegeoji.backend.support.PostgresContainerSupport;
import com.ttegeoji.backend.util.Json;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// 서비스 트랜잭션을 실제로 커밋한다. 테스트마다 새 사용자·방·게시물을 만들어 서로 섞이지 않는다
@SpringBootTest
class VerdictFallbackServiceTest extends PostgresContainerSupport {

    static final String GUILTY_LINE = "배심원단이 이 지출을 유죄로 판단했습니다.";
    static final String ONE_DAY_REASON = "형량: 징역 1일 (내일 하루 무지출)";

    @Autowired
    private VerdictFallbackService fallbackService;
    @Autowired
    private VerdictRepository verdictRepository;
    @Autowired
    private TransactionTemplate tx;
    @Autowired
    private JdbcTemplate jdbc;

    private Seed seed;

    @BeforeEach
    void setUp() {
        seed = new Seed(jdbc);
    }

    private boolean apply(UUID verdictId, boolean retryRound1) {
        return Boolean.TRUE.equals(tx.execute(s -> {
            Verdict verdict = verdictRepository.findByIdForUpdate(verdictId).orElseThrow();
            return fallbackService.applyFallback(verdict, retryRound1, "trace-fallback");
        }));
    }

    @Test
    @DisplayName("10 §4.6·§6 target_intensities 강도마다 TEMPLATE 문구 1행, 새 text_version")
    void oneTemplateRowPerTargetIntensity() {
        UUID verdictId = seed.pendingVerdict("guilty", 2, 1, "now() + interval '10 seconds'").verdictId();

        assertThat(apply(verdictId, false)).isTrue();

        List<Map<String, Object>> texts = seed.texts(verdictId);
        assertThat(texts).extracting(t -> t.get("intensity")).containsExactlyInAnyOrder("mild", "hell");
        assertThat(texts).allSatisfy(t -> {
            assertThat(t.get("source")).isEqualTo("TEMPLATE");
            assertThat(((Number) t.get("text_version")).longValue()).isEqualTo(1L);
        });
        Map<String, Object> verdict = seed.verdict(verdictId);
        assertThat(((Number) verdict.get("text_version")).longValue()).isEqualTo(1L);
        assertThat(verdict.get("text_status")).isEqualTo("TEMPLATE_READY");
        assertThat(verdict.get("retry_round")).isEqualTo(0);
        assertThat(verdict.get("pending_retry_at")).isNull();
    }

    @Test
    @DisplayName("10 §10 guilty 카드 1문장, 형량 fallback FINAL/RULE·이유 TEMPLATE")
    void guiltyTemplateSubstituted() {
        UUID verdictId = seed.pendingVerdict("guilty", 2, 1, "now() + interval '10 seconds'").verdictId();

        apply(verdictId, false);

        Map<String, Object> text = seed.texts(verdictId).getFirst();
        assertThat(text.get("headline")).isEqualTo("유죄");
        assertThat(Json.read((String) text.get("statement"))).isEqualTo(
                List.of(Map.of("text", GUILTY_LINE, "kind", "opinion", "evidence_labels", List.of())));
        Map<String, Object> verdict = seed.verdict(verdictId);
        assertThat(verdict.get("sentence_status")).isEqualTo("FINAL");
        assertThat(verdict.get("sentence")).isEqualTo("oneDay");
        assertThat(verdict.get("sentence_source")).isEqualTo("RULE");
        assertThat(verdict.get("sentencing_reason")).isEqualTo(ONE_DAY_REASON);
        assertThat(verdict.get("reason_source")).isEqualTo("TEMPLATE");
    }

    @Test
    @DisplayName("10 §4.6 notGuilty 는 형량 없이 문구만")
    void notGuiltyHasNoSentence() {
        UUID verdictId = seed.pendingVerdict("notGuilty", 1, 2, "now() + interval '10 seconds'").verdictId();

        apply(verdictId, false);

        Map<String, Object> verdict = seed.verdict(verdictId);
        assertThat(verdict.get("sentence_status")).isEqualTo("FINAL");
        assertThat(verdict.get("sentence")).isNull();
        assertThat(verdict.get("sentence_source")).isNull();
        assertThat(verdict.get("sentencing_reason")).isNull();
        assertThat(verdict.get("reason_source")).isNull();
        assertThat(seed.texts(verdictId)).extracting(t -> t.get("headline")).containsOnly("무죄");
        assertThat(seed.retainCount(verdictId)).isEqualTo(1);
    }

    @Test
    @DisplayName("10 §6 2단계 두 번 호출해도 FINAL 1회·RETAIN 1개·text_version 그대로")
    void secondCallIsNoop() {
        UUID verdictId = seed.pendingVerdict("guilty", 2, 1, "now() + interval '10 seconds'").verdictId();

        assertThat(apply(verdictId, true)).isTrue();
        assertThat(apply(verdictId, true)).isFalse();

        assertThat(seed.retainCount(verdictId)).isEqualTo(1);
        Map<String, Object> verdict = seed.verdict(verdictId);
        assertThat(((Number) verdict.get("text_version")).longValue()).isEqualTo(1L);
        assertThat(verdict.get("retry_round")).isEqualTo(1);
    }

    /**
     * begin·failed·fallback 테스트가 같이 쓰는 행 INSERT. 커밋된다(autocommit).
     * 게시물마다 새 작성자·방·배심원을 만들어 다른 테스트와 scope key 가 겹치지 않는다.
     */
    static final class Seed {

        record Case(UUID postId, UUID verdictId) {
        }

        private final JdbcTemplate jdbc;

        Seed(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        private UUID profile() {
            UUID id = UUID.randomUUID();
            jdbc.update("INSERT INTO profiles (id, nickname, monthly_budget) VALUES (?, 'n', 100000)", id);
            return id;
        }

        /** 공유 방 1개, 배심원 guilty+notGuilty 명, target_intensities ["mild","hell"], fallback oneDay(유죄일 때) */
        Case pendingVerdict(String result, int guiltyVotes, int notGuiltyVotes, String deadlineExpr) {
            UUID author = profile();
            UUID roomId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO rooms (id, name, spice_level, vote_deadline_minutes, created_by)
                    VALUES (?, 'room', CAST('mild' AS spice_level), 60, ?)
                    """, roomId, author);
            UUID postId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO posts (id, author_id, post_type, amount_krw, category, item, intake_status,
                                       intake_source, vote_deadline_at)
                    VALUES (?, ?, CAST('spent' AS post_type), 5000, '식비', '점심', 'PASS', 'AI', now())
                    """, postId, author);
            jdbc.update("INSERT INTO post_rooms (post_id, room_id) VALUES (?, ?)", postId, roomId);
            for (int i = 0; i < guiltyVotes + notGuiltyVotes; i++) {
                jdbc.update("""
                        INSERT INTO votes (post_id, voter_id, room_id, verdict, reason)
                        VALUES (?, ?, ?, CAST(? AS verdict), '이유')
                        """, postId, profile(), roomId, i < guiltyVotes ? "guilty" : "notGuilty");
            }
            String policy = "guilty".equals(result)
                    ? "{\"version\":\"sentencing-band-v1\",\"allowed_sentences\":[{\"code\":\"probation\",\"rank\":1},"
                      + "{\"code\":\"oneDay\",\"rank\":2}],\"fallback_sentence\":\"oneDay\",\"reason_required\":true}"
                    : "{\"version\":\"sentencing-band-v1\",\"allowed_sentences\":[],\"fallback_sentence\":null,"
                      + "\"reason_required\":false}";
            UUID verdictId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO verdicts (id, post_id, jury_result, policy_snapshot, confirmed_at, deadline_at,
                                          target_intensities, default_intensity)
                    VALUES (?, ?, CAST(? AS verdict), CAST(? AS jsonb), now(), %s,
                            CAST('["mild","hell"]' AS jsonb), CAST('mild' AS spice_level))
                    """.formatted(deadlineExpr), verdictId, postId, result, policy);
            return new Case(postId, verdictId);
        }

        /** 그 verdict 의 RUNNING job(aggregate_id = verdict id). leaseExpr·deadlineExpr 는 SQL 식 */
        UUID runningJob(UUID verdictId, JobKind kind, UUID generationId, String leaseExpr, String deadlineExpr) {
            UUID id = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO ai.jobs (id, event_id, event_type, kind, dedupe_key, aggregate_id, aggregate_version,
                                         payload, status, priority, attempts, max_attempts, deadline_at, lease_until,
                                         owner_id, generation_id, trace_id)
                    VALUES (?, gen_random_uuid(), 'test.event', ?, ?, ?, 1, CAST('{}' AS jsonb), 'RUNNING', 10,
                            1, 2, %s, %s, 'worker-1', ?, 'trace')
                    """.formatted(deadlineExpr, leaseExpr), id, kind.name(), "test:" + id, verdictId.toString(),
                    generationId);
            return id;
        }

        /**
         * 끝난 job 으로 치운다. lease 만료 RUNNING 을 커밋한 채 남기면 ReaperSchedulerTest 의 전역 회수 건수 단언과
         * 섞이므로 테스트 끝에 부른다.
         */
        void finishJobs(UUID... jobIds) {
            for (UUID id : jobIds) {
                jdbc.update("""
                        UPDATE ai.jobs SET status = 'CANCELLED', owner_id = NULL, generation_id = NULL, lease_until = NULL
                         WHERE id = ?
                        """, id);
            }
        }

        /** verdicts 한 행을 SET 절로 바꾼다. 테스트 상태(FINAL·active·retry_round) 준비용 */
        void updateVerdict(UUID verdictId, String setClause, Object... args) {
            Object[] params = new Object[args.length + 1];
            System.arraycopy(args, 0, params, 0, args.length);
            params[args.length] = verdictId;
            jdbc.update("UPDATE verdicts SET " + setClause + " WHERE id = ?", params);
        }

        Map<String, Object> verdict(UUID verdictId) {
            return jdbc.queryForMap("""
                    SELECT sentence_status, sentence::text AS sentence, sentence_source, sentencing_reason,
                           reason_source, text_status, text_version, active_job_id, active_generation_id,
                           retry_round, pending_retry_at, last_failed_generation_id, last_failed_code, deadline_at,
                           EXTRACT(EPOCH FROM pending_retry_at - now()) AS retry_in_seconds
                      FROM verdicts WHERE id = ?
                    """, verdictId);
        }

        List<Map<String, Object>> texts(UUID verdictId) {
            return jdbc.queryForList("""
                    SELECT intensity::text AS intensity, headline, statement::text AS statement, source, text_version
                      FROM verdict_texts WHERE verdict_id = ? ORDER BY intensity
                    """, verdictId);
        }

        int retainCount(UUID verdictId) {
            Integer count = jdbc.queryForObject("SELECT count(*) FROM ai.jobs WHERE dedupe_key LIKE ?",
                    Integer.class, "retain:verdict:" + verdictId + ":%");
            return count == null ? 0 : count;
        }
    }
}
