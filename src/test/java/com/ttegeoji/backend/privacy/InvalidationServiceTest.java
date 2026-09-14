package com.ttegeoji.backend.privacy;

import com.ttegeoji.backend.support.PostgresContainerSupport;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 실제로 커밋한다. 테스트마다 새 profile·post UUID 를 써서 섞이지 않는다.
// 다른 테스트 컨텍스트의 InvalidationScheduler 가 커밋된 PENDING 행을 먼저 처리할 수 있어 기록 행의 status 는 보지 않는다
@SpringBootTest
class InvalidationServiceTest extends PostgresContainerSupport {

    @Autowired
    private InvalidationService service;
    @Autowired
    private PrivacyEpochRepository epochs;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TransactionTemplate tx;

    private Fixtures f;

    @BeforeEach
    void setUp() {
        f = new Fixtures(jdbc);
    }

    @Test
    @DisplayName("10 §8 삭제 → post epoch +1·deleted_at·PENDING 기록이 한 트랜잭션(롤백하면 셋 다 없다)")
    void deleteIsOneTransaction() {
        UUID author = f.profile();
        UUID post = f.post(author);
        String key = ScopeKeys.post(post);

        tx.executeWithoutResult(s -> {
            service.deletePost(post, author);
            assertThat(f.epoch(key)).isEqualTo(1L);
            assertThat(f.isDeleted(post)).isTrue();
            assertThat(f.invalidations(key)).hasSize(1);
            s.setRollbackOnly();
        });
        assertThat(f.epoch(key)).isZero();
        assertThat(f.isDeleted(post)).isFalse();
        assertThat(f.invalidations(key)).isEmpty();

        service.deletePost(post, author);

        assertThat(f.epoch(key)).isEqualTo(1L);
        assertThat(f.isDeleted(post)).isTrue();
        assertThat(f.invalidations(key)).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("source_type", "POST").containsEntry("source_id", post.toString());
        });
    }

    @Test
    @DisplayName("10 §8 D-26 삭제 → 그 post 의 QUEUED·RUNNING PREPARE·SENTENCE·TEXT_RETRY CANCELLED, 세 컬럼 NULL")
    void deleteCancelsActiveJobs() {
        UUID author = f.profile();
        UUID post = f.post(author);
        UUID verdict = f.verdict(post);
        List<UUID> active = List.of(
                f.job("PREPARE", "QUEUED", Fixtures.postPayload(post)),
                f.job("PREPARE", "RUNNING", Fixtures.postPayload(post)),
                f.job("SENTENCE", "QUEUED", Fixtures.postPayload(post)),
                f.job("SENTENCE", "RUNNING", Fixtures.postPayload(post)),
                // 10 §0.1 D-26: TEXT_RETRY payload 에는 post_id 가 없고 verdict_id 로 찾힌다
                f.job("TEXT_RETRY", "QUEUED", Fixtures.verdictPayload(verdict)),
                f.job("TEXT_RETRY", "RUNNING", Fixtures.verdictPayload(verdict)));

        service.deletePost(post, author);

        for (UUID job : active) {
            assertThat(f.job(job))
                    .containsEntry("status", "CANCELLED")
                    .containsEntry("owner_id", null)
                    .containsEntry("generation_id", null)
                    .containsEntry("lease_until", null);
        }
    }

    @Test
    @DisplayName("10 §8 D-26 RETAIN·끝난 job·다른 post 의 job 은 그대로")
    void deleteLeavesOtherJobs() {
        UUID author = f.profile();
        UUID post = f.post(author);
        UUID other = f.post(author);
        UUID verdict = f.verdict(post);
        UUID otherVerdict = f.verdict(other);
        Map<UUID, String> untouched = Map.of(
                f.job("RETAIN", "QUEUED", Fixtures.postPayload(post)), "QUEUED",
                f.job("RETAIN", "RUNNING", Fixtures.postPayload(post)), "RUNNING",
                f.job("SENTENCE", "SUCCEEDED", Fixtures.postPayload(post)), "SUCCEEDED",
                f.job("PREPARE", "FAILED", Fixtures.postPayload(post)), "FAILED",
                f.job("TEXT_RETRY", "CANCELLED", Fixtures.verdictPayload(verdict)), "CANCELLED",
                f.job("PREPARE", "QUEUED", Fixtures.postPayload(other)), "QUEUED",
                f.job("TEXT_RETRY", "RUNNING", Fixtures.verdictPayload(otherVerdict)), "RUNNING");

        service.deletePost(post, author);

        untouched.forEach((job, status) -> assertThat(f.job(job)).containsEntry("status", status));
    }

    @Test
    @DisplayName("10 §8 공유 철회 → post epoch +1·revoked_at 표시(행 남음)·audience_version +1·진행 중 job CANCELLED·다른 방 그대로")
    void withdrawRoomShare() {
        UUID author = f.profile();
        UUID roomA = f.room(author);
        UUID roomB = f.room(author);
        UUID post = f.post(author);
        f.share(post, roomA);
        f.share(post, roomB);
        UUID verdict = f.verdict(post);
        UUID sentence = f.job("SENTENCE", "RUNNING", Fixtures.postPayload(post));
        UUID textRetry = f.job("TEXT_RETRY", "QUEUED", Fixtures.verdictPayload(verdict));
        UUID retain = f.job("RETAIN", "QUEUED", Fixtures.postPayload(post));
        String key = ScopeKeys.post(post);

        service.withdrawRoomShare(post, roomA);

        assertThat(f.epoch(key)).isEqualTo(1L);
        assertThat(f.revokedAt(post, roomA)).isNotNull();
        assertThat(f.revokedAt(post, roomB)).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM post_rooms WHERE post_id = ?", Integer.class, post))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT audience_version FROM posts WHERE id = ?", Integer.class, post))
                .isEqualTo(2);
        assertThat(f.isDeleted(post)).isFalse();
        for (UUID job : List.of(sentence, textRetry)) {
            assertThat(f.job(job))
                    .containsEntry("status", "CANCELLED")
                    .containsEntry("owner_id", null)
                    .containsEntry("generation_id", null)
                    .containsEntry("lease_until", null);
        }
        assertThat(f.job(retain)).containsEntry("status", "QUEUED");
        assertThat(f.invalidations(key)).hasSize(1);
    }

    @Test
    @DisplayName("10 §8 작성자 아닌 사용자 삭제 → 거부, epoch·deleted_at·job·기록 변화 없음")
    void nonAuthorRejected() {
        UUID author = f.profile();
        UUID stranger = f.profile();
        UUID post = f.post(author);
        UUID job = f.job("PREPARE", "QUEUED", Fixtures.postPayload(post));
        String key = ScopeKeys.post(post);

        assertThatThrownBy(() -> service.deletePost(post, stranger)).isInstanceOf(IllegalStateException.class);

        assertThat(f.epoch(key)).isZero();
        assertThat(f.isDeleted(post)).isFalse();
        assertThat(f.job(job)).containsEntry("status", "QUEUED");
        assertThat(f.invalidations(key)).isEmpty();
    }

    @Test
    @DisplayName("10 §8 5문단 여러 방에 공유된 게시물 삭제 → 어느 privacy_versions 조합과도 달라지고 job·기록 누락 없음")
    void multiRoomDeleteMissesNoScope() {
        UUID author = f.profile();
        UUID post = f.post(author);
        List<UUID> rooms = List.of(f.room(author), f.room(author), f.room(author));
        rooms.forEach(room -> f.share(post, room));
        UUID verdict = f.verdict(post);
        List<UUID> jobs = List.of(
                f.job("PREPARE", "QUEUED", Fixtures.postPayload(post)),
                f.job("SENTENCE", "RUNNING", Fixtures.postPayload(post)),
                f.job("TEXT_RETRY", "QUEUED", Fixtures.verdictPayload(verdict)));
        // case-snapshot privacy_versions 모양: post·author·각 room(10 §4.1)
        List<String> keys = new java.util.ArrayList<>(List.of(ScopeKeys.post(post), ScopeKeys.user(author)));
        rooms.forEach(room -> keys.add(ScopeKeys.room(room)));
        Map<String, Long> before = epochs.read(keys);

        service.deletePost(post, author);

        Map<String, Long> after = epochs.read(keys);
        assertThat(after).isNotEqualTo(before);
        // 방마다 따로 저장된 조합(post + 그 방 하나)도 모두 달라진다
        for (UUID room : rooms) {
            List<String> subset = List.of(ScopeKeys.post(post), ScopeKeys.room(room));
            assertThat(epochs.read(subset)).isNotEqualTo(Fixtures.pick(before, subset));
        }
        jobs.forEach(job -> assertThat(f.job(job)).containsEntry("status", "CANCELLED"));
        assertThat(f.invalidations(ScopeKeys.post(post))).hasSize(1);
    }

    @Test
    @DisplayName("10 §5 3단계·§13 작업 8 retry 중 삭제 → 삭제 전 스냅샷 epoch ≠ 현재 epoch")
    void epochChangesAcrossDelete() {
        UUID author = f.profile();
        UUID post = f.post(author);
        String key = ScopeKeys.post(post);
        tx.executeWithoutResult(s -> {
            epochs.bump(key);
            epochs.bump(key);
            epochs.bump(key);
        });
        long snapshot = epochs.read(List.of(key)).get(key);

        service.deletePost(post, author);

        assertThat(snapshot).isEqualTo(3L);
        assertThat(epochs.read(List.of(key)).get(key)).isEqualTo(4L).isNotEqualTo(snapshot);
    }

    @Test
    @DisplayName("10 §2·§8 invalidate 여러 scope → key 오름차순으로 epoch +1, scope 마다 기록 1행")
    void invalidateManyScopes() {
        String suffix = UUID.randomUUID().toString();
        String room = "room:" + suffix;
        String post = "post:" + suffix;
        String user = "user:" + suffix;
        String commentId = UUID.randomUUID().toString();

        service.invalidate(List.of(user, room, post, room), "COMMENT", commentId);

        assertThat(epochs.read(List.of(room, post, user)))
                .containsEntry(room, 1L).containsEntry(post, 1L).containsEntry(user, 1L);
        assertThat(jdbc.queryForList("SELECT scope_key FROM privacy_invalidations WHERE source_id = ? ORDER BY id",
                String.class, commentId))
                .containsExactly(post, room, user);
    }

    /** privacy 테스트 공용 픽스처. 행은 JdbcTemplate 으로 직접 넣는다(Schema004Test 선례) */
    static final class Fixtures {

        private final JdbcTemplate jdbc;

        Fixtures(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        static String postPayload(UUID postId) {
            return "{\"post_id\": \"" + postId + "\"}";
        }

        static String verdictPayload(UUID verdictId) {
            return "{\"verdict_id\": \"" + verdictId + "\"}";
        }

        static Map<String, Long> pick(Map<String, Long> map, List<String> keys) {
            Map<String, Long> picked = new java.util.TreeMap<>();
            keys.forEach(k -> picked.put(k, map.get(k)));
            return picked;
        }

        UUID profile() {
            UUID id = UUID.randomUUID();
            jdbc.update("INSERT INTO profiles (id, nickname, monthly_budget) VALUES (?, 'n', 0)", id);
            return id;
        }

        UUID room(UUID createdBy) {
            return jdbc.queryForObject("""
                    INSERT INTO rooms (name, spice_level, vote_deadline_minutes, created_by)
                    VALUES ('room', 'mild', 30, ?) RETURNING id""", UUID.class, createdBy);
        }

        UUID post(UUID author) {
            return jdbc.queryForObject("""
                    INSERT INTO posts (author_id, post_type, amount_krw, category, item, intake_status, intake_source, vote_deadline_at)
                    VALUES (?, 'spent', 5000, '식비', 'item', 'PASS', 'AI', now() + interval '30 minutes') RETURNING id""",
                    UUID.class, author);
        }

        void share(UUID post, UUID room) {
            jdbc.update("INSERT INTO post_rooms (post_id, room_id) VALUES (?, ?)", post, room);
        }

        UUID verdict(UUID post) {
            return jdbc.queryForObject("""
                    INSERT INTO verdicts (post_id, jury_result, policy_snapshot, confirmed_at, target_intensities,
                                          default_intensity, sentence_status, sentence, sentence_source, text_status, text_version)
                    VALUES (?, 'guilty', '{}'::jsonb, now(), '["mild","spicy"]'::jsonb, 'mild', 'FINAL', 'oneDay', 'RULE',
                            'AI_READY', 2) RETURNING id""", UUID.class, post);
        }

        UUID job(String kind, String status, String payloadJson) {
            UUID id = UUID.randomUUID();
            boolean running = "RUNNING".equals(status);
            jdbc.update("""
                    INSERT INTO ai.jobs (id, event_id, event_type, kind, dedupe_key, aggregate_id, aggregate_version,
                                         payload, status, priority, attempts, max_attempts, lease_until, owner_id,
                                         generation_id, trace_id)
                    VALUES (?, gen_random_uuid(), 'test.event', ?, ?, 'agg', 1, CAST(? AS jsonb), ?, 10, 0, 2,
                            %s, ?, ?, 'trace')""".formatted(running ? "now() + interval '1 minute'" : "NULL"),
                    id, kind, "test:" + id, payloadJson, status,
                    running ? "worker-1" : null, running ? UUID.randomUUID() : null);
            return id;
        }

        Map<String, Object> job(UUID id) {
            return jdbc.queryForMap("SELECT status, owner_id, generation_id, lease_until FROM ai.jobs WHERE id = ?", id);
        }

        long epoch(String scopeKey) {
            Long epoch = jdbc.queryForObject(
                    "SELECT COALESCE((SELECT epoch FROM ai.privacy_epochs WHERE scope_key = ?), 0)", Long.class, scopeKey);
            return epoch == null ? 0L : epoch;
        }

        boolean isDeleted(UUID post) {
            return Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT deleted_at IS NOT NULL FROM posts WHERE id = ?", Boolean.class, post));
        }

        Object revokedAt(UUID post, UUID room) {
            return jdbc.queryForObject("SELECT revoked_at FROM post_rooms WHERE post_id = ? AND room_id = ?",
                    Object.class, post, room);
        }

        List<Map<String, Object>> invalidations(String scopeKey) {
            return jdbc.queryForList("""
                    SELECT id, scope_key, source_type, source_id, status, attempts, last_error, processed_at
                      FROM privacy_invalidations WHERE scope_key = ? ORDER BY id""", scopeKey);
        }

        long pendingInvalidation(String scopeKey, String sourceType, String sourceId) {
            Long id = jdbc.queryForObject("""
                    INSERT INTO privacy_invalidations (scope_key, source_type, source_id) VALUES (?, ?, ?) RETURNING id""",
                    Long.class, scopeKey, sourceType, sourceId);
            return id == null ? 0L : id;
        }

        Map<String, Object> invalidation(long id) {
            return jdbc.queryForMap("""
                    SELECT status, attempts, last_error, processed_at FROM privacy_invalidations WHERE id = ?""", id);
        }

        /** AI 쪽 파생 데이터 한 벌. 출처는 ('POST', postId), node_results privacy_versions 는 [{scope_key, epoch}] */
        AiCase aiCase(String postId, String scopeKey) {
            String privacyVersions = "[{\"scope_key\": \"" + scopeKey + "\", \"epoch\": 1}]";
            UUID dossier = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO ai.dossiers (id, post_id, snapshot_hash, label_map, privacy_versions)
                    VALUES (?, ?, 'h', '{}'::jsonb, CAST(? AS jsonb))""", dossier, postId, privacyVersions);
            UUID evidence = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO ai.evidence (id, dossier_id, label, epistemic_type, fact_type, text, scope)
                    VALUES (?, ?, 'F1', 'DB_RECORD', 'SPEND', 'text', '{}'::jsonb)""", evidence, dossier);
            jdbc.update("""
                    INSERT INTO ai.evidence_sources (evidence_id, source_type, source_id, source_version)
                    VALUES (?, 'POST', ?, 1)""", evidence, postId);
            UUID trialPrep = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO ai.trial_prep (id, post_id, post_version, audience_version, privacy_versions, prompt_version,
                                               input_hash, status, dossier_id)
                    VALUES (?, ?, 1, 1, CAST(? AS jsonb), 'p1', ?, 'DOSSIER_READY', ?)""",
                    trialPrep, postId, privacyVersions, UUID.randomUUID().toString(), dossier);
            UUID memoryFact = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO ai.memory_facts (id, bank_type, bank_id, fact_type, epistemic_type, source_type, source_id,
                                                 source_version, payload, scope, occurred_at)
                    VALUES (?, 'USER', 'u', 'SPEND', 'DB_RECORD', 'POST', ?, 1, '{}'::jsonb, '{}'::jsonb, now())""",
                    memoryFact, postId);
            UUID call = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO ai.llm_calls (id, generation_id, node, call_index, vendor, model_id, request_hash, status,
                                              estimated_max_micro_usd)
                    VALUES (?, gen_random_uuid(), 'writer', 0, 'v', 'm', 'h', 'COMPLETE', 1)""", call);
            jdbc.update("""
                    INSERT INTO ai.node_results (call_id, request_hash, model_id, prompt_version, policy_version,
                                                 privacy_versions, validated_output, expires_at)
                    VALUES (?, 'h', 'm', 'p', 'g', CAST(? AS jsonb), '{}'::jsonb, now() + interval '1 day')""",
                    call, privacyVersions);
            return new AiCase(dossier, evidence, trialPrep, memoryFact, call);
        }

        /** 각 파생 행의 무효화 표시. 멱등 비교에 쓰려고 시각을 문자열로 */
        Map<String, Object> aiState(AiCase c) {
            return jdbc.queryForMap("""
                    SELECT (SELECT invalidated_at::text FROM ai.evidence WHERE id = ?) AS evidence,
                           (SELECT invalidated_at::text FROM ai.dossiers WHERE id = ?) AS dossier,
                           (SELECT status || ' ' || coalesce(invalidated_at::text, '-') FROM ai.trial_prep WHERE id = ?) AS trial_prep,
                           (SELECT deleted_at::text FROM ai.memory_facts WHERE id = ?) AS memory_fact,
                           (SELECT invalidated_at::text FROM ai.node_results WHERE call_id = ?) AS node_result""",
                    c.evidence(), c.dossier(), c.trialPrep(), c.memoryFact(), c.call());
        }

        void verdictText(UUID verdict, String intensity, long textVersion) {
            jdbc.update("""
                    INSERT INTO verdict_texts (verdict_id, intensity, headline, statement, source, text_version)
                    VALUES (?, CAST(? AS spice_level), 'AI 헤드라인', '[]'::jsonb, 'AI', ?)""", verdict, intensity, textVersion);
        }

        void ref(UUID verdict, long textVersion, String intensity, UUID evidence) {
            jdbc.update("""
                    INSERT INTO ai.text_evidence_refs (verdict_id, text_version, intensity, field_path, evidence_id)
                    VALUES (?, ?, ?, 'statement[0]', ?)""", verdict, textVersion, intensity, evidence);
        }
    }

    record AiCase(UUID dossier, UUID evidence, UUID trialPrep, UUID memoryFact, UUID call) {
    }
}
