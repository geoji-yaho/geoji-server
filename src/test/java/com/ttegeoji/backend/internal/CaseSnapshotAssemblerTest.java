package com.ttegeoji.backend.internal;

import com.ttegeoji.backend.config.InternalApiException;
import com.ttegeoji.backend.jobs.JobKind;
import com.ttegeoji.backend.jobs.JobQueries;
import com.ttegeoji.backend.jobs.JobRow;
import com.ttegeoji.backend.privacy.ScopeKeys;
import com.ttegeoji.backend.support.PostgresContainerSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// 행은 테스트 트랜잭션 안에서만 보인다(롤백). 응답은 앱의 ObjectMapper 로 직렬화해 워커가 받는 JSON 그대로 본다.
@SpringBootTest
@Transactional
class CaseSnapshotAssemblerTest extends PostgresContainerSupport {

    private static final UUID GENERATION = UUID.randomUUID();
    private static final String LEASE = "now() + interval '30 seconds'";

    @Autowired
    private CaseSnapshotAssembler assembler;
    @Autowired
    private JobQueries jobQueries;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ObjectMapper objectMapper;

    private InternalFixtures fx;
    private UUID author;

    @BeforeEach
    void setUp() {
        fx = new InternalFixtures(jdbcTemplate);
        author = fx.profile();
    }

    private JsonNode snapshot(JobKind kind, String payload) {
        UUID jobId = fx.runningJob(kind, payload, GENERATION, LEASE);
        JobRow job = jobQueries.findJob(jobId).orElseThrow();
        JsonNode node = objectMapper.readTree(objectMapper.writeValueAsString(assembler.assemble(job)));
        CaseSnapshotShape.assertValid(node);
        return node;
    }

    private InternalApiException rejected(JobKind kind, String payload) {
        UUID jobId = fx.runningJob(kind, payload, GENERATION, LEASE);
        JobRow job = jobQueries.findJob(jobId).orElseThrow();
        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> assembler.assemble(job));
        assertThat(thrown).isInstanceOf(InternalApiException.class);
        return (InternalApiException) thrown;
    }

    private UUID sharedPost(String postType, UUID... rooms) {
        UUID post = fx.post(author, postType);
        for (UUID room : rooms) {
            fx.share(post, room);
        }
        return post;
    }

    @Test
    @DisplayName("10 §4.1 PREPARE 스냅샷은 jury=null")
    void prepareHasNoJury() {
        UUID room = fx.room(author, "spicy", 1);
        UUID post = sharedPost("spent", room);

        JsonNode n = snapshot(JobKind.PREPARE, InternalFixtures.preparePayload(post));

        assertThat(n.has("jury")).isTrue();
        assertThat(n.get("jury").isNull()).isTrue();
        assertThat(n.get("post_id").stringValue()).isEqualTo(post.toString());
    }

    @Test
    @DisplayName("10 §4.1 §0.1 9/11 SENTENCE 는 jury 를 채우고 guilty 3·notGuilty 1 → guilty_ratio 0.75(백분율 아님)")
    void sentenceFillsJury() {
        UUID room = fx.room(author, "spicy", 1);
        UUID post = sharedPost("spent", room);
        for (int i = 0; i < 3; i++) {
            fx.vote(post, room, "guilty");
        }
        fx.vote(post, room, "notGuilty");
        UUID verdict = fx.verdict(post, "guilty");

        JsonNode jury = snapshot(JobKind.SENTENCE, InternalFixtures.sentencePayload(verdict, post)).get("jury");

        assertThat(jury.isObject()).isTrue();
        assertThat(jury.get("verdict_id").stringValue()).isEqualTo(verdict.toString());
        assertThat(jury.get("verdict_version").intValue()).isEqualTo(1);
        assertThat(jury.get("result").stringValue()).isEqualTo("guilty");
        assertThat(jury.get("guilty_ratio").doubleValue()).isEqualTo(0.75);
        assertThat(jury.get("vote_counts").propertyNames()).containsExactlyInAnyOrder("guilty", "notGuilty");
        assertThat(jury.get("vote_counts").get("guilty").intValue()).isEqualTo(3);
        assertThat(jury.get("vote_counts").get("notGuilty").intValue()).isEqualTo(1);
        assertThat(objectMapper.treeToValue(jury.get("policy"), Map.class))
                .isEqualTo(objectMapper.readValue(InternalFixtures.POLICY_JSON, Map.class));
        List<String> targets = new ArrayList<>();
        jury.get("target_intensities").forEach(t -> targets.add(t.stringValue()));
        assertThat(targets).containsExactly("mild", "spicy");
        assertThat(jury.get("default_intensity").stringValue()).isEqualTo("spicy");
    }

    @Test
    @DisplayName("10 §4.1·§3 TEXT_RETRY 는 payload 에 post_id 가 없어 verdict 로 post 를 찾고 jury 를 채운다")
    void textRetryFindsPostThroughVerdict() {
        UUID room = fx.room(author, "hell", 1);
        UUID post = sharedPost("spent", room);
        fx.vote(post, room, "guilty");
        UUID verdict = fx.verdict(post, "guilty");

        JsonNode n = snapshot(JobKind.TEXT_RETRY, InternalFixtures.textRetryPayload(verdict));

        assertThat(n.get("post_id").stringValue()).isEqualTo(post.toString());
        assertThat(n.get("jury").get("verdict_id").stringValue()).isEqualTo(verdict.toString());
        assertThat(n.get("jury").get("guilty_ratio").doubleValue()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("10 §4.1 + 9/15 결정 considering agree 1·disagree 3 → guilty_ratio 0.75")
    void consideringRatio() {
        UUID room = fx.room(author, "mild", 1);
        UUID post = sharedPost("considering", room);
        fx.vote(post, room, "agree");
        for (int i = 0; i < 3; i++) {
            fx.vote(post, room, "disagree");
        }
        UUID verdict = fx.verdict(post, "disagree");

        JsonNode jury = snapshot(JobKind.SENTENCE, InternalFixtures.sentencePayload(verdict, post)).get("jury");

        assertThat(jury.get("guilty_ratio").doubleValue()).isEqualTo(0.75);
        assertThat(jury.get("vote_counts").get("agree").intValue()).isEqualTo(1);
        assertThat(jury.get("vote_counts").get("disagree").intValue()).isEqualTo(3);
        assertThat(jury.get("vote_counts").propertyNames()).containsExactlyInAnyOrder("agree", "disagree");
    }

    @Test
    @DisplayName("9/15 결정 표 0 → guilty_ratio 0, vote_counts 0 포함")
    void zeroVotes() {
        UUID room = fx.room(author, "spicy", 1);
        UUID post = sharedPost("spent", room);
        UUID verdict = fx.verdict(post, "guilty");

        JsonNode jury = snapshot(JobKind.SENTENCE, InternalFixtures.sentencePayload(verdict, post)).get("jury");

        assertThat(jury.get("guilty_ratio").doubleValue()).isEqualTo(0.0);
        assertThat(jury.get("vote_counts").get("guilty").intValue()).isZero();
        assertThat(jury.get("vote_counts").get("notGuilty").intValue()).isZero();
    }

    @Test
    @DisplayName("10 §4.1 §0.1 9/11 평면 — 최상위에 post_id·item 등 10키, post 키 없음, 알려진 키 밖 없음")
    void flatTopLevel() {
        UUID room = fx.room(author, "spicy", 1);
        UUID post = sharedPost("spent", room);

        JsonNode n = snapshot(JobKind.PREPARE, InternalFixtures.preparePayload(post));

        List<String> allowed = new ArrayList<>(CaseSnapshotShape.TOP_REQUIRED);
        allowed.addAll(CaseSnapshotShape.TOP_OPTIONAL);
        assertThat(n.propertyNames()).doesNotContain("post").isSubsetOf(allowed)
                .containsAll(CaseSnapshotShape.TOP_REQUIRED);
        assertThat(n.get("schema_version").intValue()).isEqualTo(1);
        assertThat(n.get("author_id").stringValue()).isEqualTo(author.toString());
        assertThat(n.get("post_version").intValue()).isEqualTo(1);
        assertThat(n.get("item").stringValue()).isEqualTo("택시");
        assertThat(n.get("reason").stringValue()).isEqualTo("늦잠 자서 택시 탐");
        assertThat(n.get("amount_krw").intValue()).isEqualTo(12000);
        assertThat(n.get("category").stringValue()).isEqualTo("교통/택시");
        assertThat(n.get("post_type").stringValue()).isEqualTo("spent");
        assertThat(n.get("created_at").stringValue()).endsWith("Z");
    }

    @Test
    @DisplayName("10 §4.1·§2 privacy_versions = post·author·방마다 scope, 행 없으면 epoch 0·있으면 그 값")
    void privacyVersions() {
        UUID roomA = fx.room(author, "spicy", 1);
        UUID roomB = fx.room(author, "hell", 1);
        UUID post = sharedPost("spent", roomA, roomB);
        fx.epoch(ScopeKeys.user(author), 3);
        fx.epoch(ScopeKeys.room(roomB), 2);

        JsonNode n = snapshot(JobKind.PREPARE, InternalFixtures.preparePayload(post));

        Map<String, Long> epochs = new HashMap<>();
        n.get("privacy_versions").forEach(p -> epochs.put(p.get("scope_key").stringValue(), p.get("epoch").longValue()));
        assertThat(n.get("privacy_versions").size()).isEqualTo(4);
        assertThat(epochs).containsExactlyInAnyOrderEntriesOf(Map.of(
                ScopeKeys.post(post), 0L,
                ScopeKeys.user(author), 3L,
                ScopeKeys.room(roomA), 0L,
                ScopeKeys.room(roomB), 2L));
    }

    @Test
    @DisplayName("10 §4.1 + 9/14 A안 room_snapshots 방마다 {room_id, intensity=spice_level, rule_version}")
    void roomSnapshots() {
        UUID roomA = fx.room(author, "spicy", 2, "배달 금지");
        UUID roomB = fx.room(author, "hell", 1);
        UUID post = sharedPost("spent", roomA, roomB);

        JsonNode n = snapshot(JobKind.PREPARE, InternalFixtures.preparePayload(post));

        Map<String, String> intensity = new HashMap<>();
        Map<String, Integer> ruleVersion = new HashMap<>();
        n.get("room_snapshots").forEach(r -> {
            intensity.put(r.get("room_id").stringValue(), r.get("intensity").stringValue());
            ruleVersion.put(r.get("room_id").stringValue(), r.get("rule_version").intValue());
        });
        assertThat(intensity).containsExactlyInAnyOrderEntriesOf(
                Map.of(roomA.toString(), "spicy", roomB.toString(), "hell"));
        assertThat(ruleVersion).containsExactlyInAnyOrderEntriesOf(
                Map.of(roomA.toString(), 2, roomB.toString(), 1));
    }

    @Test
    @DisplayName("10 §4.1 + 9/15 결정 audience{room_ids, audience_version, public_share_enabled=false}")
    void audience() {
        UUID roomA = fx.room(author, "spicy", 1);
        UUID roomB = fx.room(author, "mild", 1);
        UUID post = sharedPost("spent", roomA, roomB);
        jdbcTemplate.update("UPDATE posts SET audience_version = 2 WHERE id = ?", post);

        JsonNode audience = snapshot(JobKind.PREPARE, InternalFixtures.preparePayload(post)).get("audience");

        Set<String> roomIds = new HashSet<>();
        audience.get("room_ids").forEach(id -> roomIds.add(id.stringValue()));
        assertThat(roomIds).containsExactlyInAnyOrder(roomA.toString(), roomB.toString());
        assertThat(audience.get("audience_version").intValue()).isEqualTo(2);
        assertThat(audience.get("public_share_enabled").booleanValue()).isFalse();
    }

    @Test
    @DisplayName("9/15 결정 audience.public_share_enabled 는 posts.public_share_enabled 값을 읽는다(true)")
    void audiencePublicShareEnabled() {
        UUID room = fx.room(author, "spicy", 1);
        UUID post = sharedPost("spent", room);
        jdbcTemplate.update("UPDATE posts SET public_share_enabled = true WHERE id = ?", post);

        JsonNode audience = snapshot(JobKind.PREPARE, InternalFixtures.preparePayload(post)).get("audience");

        assertThat(audience.get("public_share_enabled").booleanValue()).isTrue();
    }

    @Test
    @DisplayName("10 §4.1 §0.1 9/14 RETAIN sentence.finalized → verdict_final 6키 + jury")
    void retainVerdictExtension() {
        UUID room = fx.room(author, "spicy", 1);
        UUID post = sharedPost("spent", room);
        fx.vote(post, room, "guilty");
        fx.vote(post, room, "guilty");
        UUID verdict = fx.verdict(post, "guilty");
        fx.finalizeVerdict(verdict, "oneDay", "AI", "늦잠은 핑계다", "AI", "spicy");

        JsonNode n = snapshot(JobKind.RETAIN, InternalFixtures.retainVerdictPayload(verdict));

        JsonNode vf = n.get("verdict_final");
        assertThat(vf.propertyNames()).containsExactlyInAnyOrder("sentence", "sentence_source", "sentencing_reason",
                "reason_source", "applied_intensity", "banter_strategy");
        assertThat(vf.get("sentence").stringValue()).isEqualTo("oneDay");
        assertThat(vf.get("sentence_source").stringValue()).isEqualTo("AI");
        assertThat(vf.get("sentencing_reason").stringValue()).isEqualTo("늦잠은 핑계다");
        assertThat(vf.get("reason_source").stringValue()).isEqualTo("AI");
        assertThat(vf.get("applied_intensity").stringValue()).isEqualTo("spicy");
        assertThat(vf.has("banter_strategy")).isTrue();
        assertThat(n.get("jury").get("verdict_id").stringValue()).isEqualTo(verdict.toString());
        assertThat(n.get("jury").get("guilty_ratio").doubleValue()).isEqualTo(1.0);
        assertThat(n.get("comment").isNull()).isTrue();
    }

    @Test
    @DisplayName("10 §4.1 §0.1 9/14 RETAIN 원본 게시물 삭제(deleted_at) → 404 NOT_FOUND")
    void retainDeletedPostNotFound() {
        UUID room = fx.room(author, "spicy", 1);
        UUID post = sharedPost("spent", room);
        UUID verdict = fx.verdict(post, "guilty");
        fx.finalizeVerdict(verdict, "probation", "RULE", null, "TEMPLATE", "mild");
        fx.deletePost(post);

        InternalApiException e = rejected(JobKind.RETAIN, InternalFixtures.retainVerdictPayload(verdict));

        assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(e.getCode()).isEqualTo("NOT_FOUND");
    }

    @Test
    @DisplayName("10 §4.1 RETAIN comment.approved 원본 댓글 조회 결과 없음 → 404 NOT_FOUND")
    void retainCommentMissingNotFound() {
        InternalApiException e = rejected(JobKind.RETAIN, InternalFixtures.retainCommentPayload(UUID.randomUUID()));

        assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(e.getCode()).isEqualTo("NOT_FOUND");
    }

    @Test
    @DisplayName("10 §2·§4.1 reason null → 키가 있고 값 null")
    void nullReasonKeepsKey() {
        UUID room = fx.room(author, "spicy", 1);
        UUID post = fx.post(author, "spent", null, null);
        fx.share(post, room);

        String json = objectMapper.writeValueAsString(assembler.assemble(jobQueries.findJob(
                fx.runningJob(JobKind.PREPARE, InternalFixtures.preparePayload(post), GENERATION, LEASE)).orElseThrow()));
        JsonNode n = objectMapper.readTree(json);

        CaseSnapshotShape.assertValid(n);
        assertThat(n.has("reason")).isTrue();
        assertThat(n.get("reason").isNull()).isTrue();
        assertThat(json).contains("\"reason\":null");
    }

    @Test
    @DisplayName("10 §4.1 intake_result: submission 없음 → null(키 유지)")
    void intakeResultNullWithoutSubmission() {
        UUID room = fx.room(author, "spicy", 1);
        UUID post = sharedPost("spent", room);

        JsonNode n = snapshot(JobKind.PREPARE, InternalFixtures.preparePayload(post));

        assertThat(n.has("intake_result")).isTrue();
        assertThat(n.get("intake_result").isNull()).isTrue();
    }

    @Test
    @DisplayName("10 §4.1 intake_result: submissions.intake_result jsonb 를 그대로 싣는다")
    void intakeResultPassesThrough() {
        String intake = """
                {"schema_version": 1, "mode": "FINAL_CHECK", "status": "PASS",
                 "item_review": {"status": "OK", "suggested_item": null}, "message": null,
                 "category_review": {"status": "OK", "suggested_category": null, "confidence": 0.9},
                 "injection_detected": false, "intake_source": "AI"}""";
        UUID room = fx.room(author, "spicy", 1);
        UUID post = fx.post(author, "spent", "사유", fx.submission(author, intake));
        fx.share(post, room);

        JsonNode n = snapshot(JobKind.PREPARE, InternalFixtures.preparePayload(post));

        assertThat(n.get("intake_result")).isEqualTo(objectMapper.readTree(intake));
    }
}
