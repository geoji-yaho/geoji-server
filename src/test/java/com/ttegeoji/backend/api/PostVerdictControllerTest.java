package com.ttegeoji.backend.api;

import com.ttegeoji.backend.privacy.PrivacyEpochRepository;
import com.ttegeoji.backend.privacy.ScopeKeys;
import com.ttegeoji.backend.support.PostgresContainerSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 커밋되는 컨테이너 DB 를 공유하므로 테스트마다 새 UUID 로 격리한다.
// 직접 넣은 PENDING verdict 는 SENTENCE job 을 같이 넣어 JuryScheduler 게이트가 건드리지 않게 하고(deadline_at NULL),
// FINAL verdict 는 pending_retry_at NULL 이라 watchdog·재시도 스케줄러 대상이 아니다
@SpringBootTest
@AutoConfigureMockMvc
class PostVerdictControllerTest extends PostgresContainerSupport {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    static final String GUILTY_TEMPLATE_LINE_2_OF_2 = "배심원단이 이 지출을 유죄로 판단했습니다.";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PrivacyEpochRepository epochs;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private Fixtures f;
    private UUID author;
    private UUID mildMember;
    private UUID spicyMember;
    private UUID outsider;
    private UUID mildRoom;
    private UUID spicyRoom;
    private UUID post;

    @BeforeEach
    void seed() {
        f = new Fixtures(jdbc);
        author = f.profile();
        mildMember = f.profile();
        spicyMember = f.profile();
        outsider = f.profile();
        mildRoom = f.room(author, "mild");
        spicyRoom = f.room(author, "spicy");
        f.member(mildRoom, author);
        f.member(mildRoom, mildMember);
        f.member(spicyRoom, author);
        f.member(spicyRoom, spicyMember);
        post = f.post(author, "spent", "now() + interval '1 hour'");
        f.share(post, mildRoom);
        f.share(post, spicyRoom);
    }

    static RequestPostProcessor as(UUID userId) {
        return jwt().jwt(token -> token.subject(userId.toString()));
    }

    private String url(UUID postId) {
        return "/api/posts/" + postId + "/verdict";
    }

    private UUID aiReadyGuilty() {
        UUID verdict = f.verdict(post, "guilty", "FINAL", "oneDay", "AI 양형 이유", "AI_READY", 2, "mild");
        f.text(verdict, "mild", "AI 순한 헤드라인", List.of("AI 순한 문장 1", "AI 순한 문장 2"), "AI", 2,
                f.snapshot(post, 0));
        f.text(verdict, "spicy", "AI 매운 헤드라인", List.of("AI 매운 문장"), "AI", 2, f.snapshot(post, 0));
        return verdict;
    }

    @Test
    @DisplayName("10 §9 투표 중(verdicts 없음) → 200, juryStatus null·view null·pollAfterMs 5000")
    void votingInProgress() throws Exception {
        mockMvc.perform(get(url(post)).with(as(author)))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"schemaVersion": 1, "postId": "%s", "juryStatus": null, "sentenceStatus": "PENDING",
                         "textStatus": "PENDING", "textVersion": 0, "view": null, "pollAfterMs": 5000}
                        """.formatted(post), JsonCompareMode.STRICT));
    }

    @Test
    @DisplayName("10 §9 text_status PENDING → 200, view null·pollAfterMs 1000")
    void pendingHasNoView() throws Exception {
        UUID verdict = f.verdict(post, "guilty", "PENDING", null, null, "PENDING", 0, "mild");
        f.blockSentenceGate(verdict);

        mockMvc.perform(get(url(post)).with(as(mildMember)))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"schemaVersion": 1, "postId": "%s", "juryStatus": "guilty", "sentenceStatus": "PENDING",
                         "textStatus": "PENDING", "textVersion": 0, "view": null, "pollAfterMs": 1000}
                        """.formatted(post), JsonCompareMode.STRICT));
    }

    @Test
    @DisplayName("10 §9 TEMPLATE_READY → 템플릿 view·pollAfterMs 30000·source TEMPLATE 면 sentencingReason null")
    void templateReadyShowsTemplateView() throws Exception {
        UUID verdict = f.verdict(post, "guilty", "FINAL", "oneDay", "형량: 징역 1일 (내일 하루 무지출)",
                "TEMPLATE_READY", 1, "mild");
        f.text(verdict, "mild", "유죄", List.of("저장된 템플릿 문장"), "TEMPLATE", 1, null);

        mockMvc.perform(get(url(post)).with(as(author)))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"schemaVersion": 1, "postId": "%s", "juryStatus": "guilty", "sentenceStatus": "FINAL",
                         "textStatus": "TEMPLATE_READY", "textVersion": 1,
                         "view": {"intensity": "mild", "headline": "유죄", "statement": ["저장된 템플릿 문장"],
                                  "sentence": "oneDay", "sentenceLabel": "징역 1일 (내일 하루 무지출)",
                                  "sentencingReason": null, "source": "TEMPLATE", "meme": null},
                         "pollAfterMs": 30000}
                        """.formatted(post), JsonCompareMode.STRICT));
    }

    @Test
    @DisplayName("10 §9 AI_READY → AI view·pollAfterMs 0·sentencingReason 값·meme metadata")
    void aiReadyShowsAiView() throws Exception {
        UUID verdict = aiReadyGuilty();
        UUID meme = f.meme("GUILTY_LIGHT", "https://cdn.example/meme.png");
        f.attachMeme(verdict, meme);

        mockMvc.perform(get(url(post)).with(as(mildMember)))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"schemaVersion": 1, "postId": "%s", "juryStatus": "guilty", "sentenceStatus": "FINAL",
                         "textStatus": "AI_READY", "textVersion": 2,
                         "view": {"intensity": "mild", "headline": "AI 순한 헤드라인",
                                  "statement": ["AI 순한 문장 1", "AI 순한 문장 2"],
                                  "sentence": "oneDay", "sentenceLabel": "징역 1일 (내일 하루 무지출)",
                                  "sentencingReason": "AI 양형 이유", "source": "AI",
                                  "meme": {"tag": "GUILTY_LIGHT", "imageId": "%s",
                                           "imageUrl": "https://cdn.example/meme.png"}},
                         "pollAfterMs": 0}
                        """.formatted(post, meme), JsonCompareMode.STRICT));
    }

    @Test
    @DisplayName("10 §9 room_id 가 오면 그 방 강도(spicy) 행")
    void roomIdSelectsRoomIntensity() throws Exception {
        aiReadyGuilty();

        mockMvc.perform(get(url(post)).param("room_id", spicyRoom.toString()).with(as(spicyMember)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.view.intensity").value("spicy"))
                .andExpect(jsonPath("$.view.headline").value("AI 매운 헤드라인"));
    }

    @Test
    @DisplayName("10 §9 room_id 가 없으면 applied_intensity(mild) 행")
    void noRoomIdUsesAppliedIntensity() throws Exception {
        aiReadyGuilty();

        mockMvc.perform(get(url(post)).with(as(spicyMember)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.view.intensity").value("mild"))
                .andExpect(jsonPath("$.view.headline").value("AI 순한 헤드라인"));
    }

    @Test
    @DisplayName("10 §9 공유 방 멤버가 아님 → 404 {message}. 멤버라도 자기가 속하지 않은 방 room_id → 404")
    void nonMemberIs404() throws Exception {
        aiReadyGuilty();

        mockMvc.perform(get(url(post)).with(as(outsider)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").exists());
        mockMvc.perform(get(url(post)).param("room_id", spicyRoom.toString()).with(as(mildMember)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(url(UUID.randomUUID())).with(as(author)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("10 §8 공유가 철회된 방의 멤버 → 404(room_id 있어도 없어도)")
    void revokedRoomMemberIs404() throws Exception {
        aiReadyGuilty();
        f.revoke(post, spicyRoom);

        mockMvc.perform(get(url(post)).with(as(spicyMember)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(url(post)).param("room_id", spicyRoom.toString()).with(as(spicyMember)))
                .andExpect(status().isNotFound());
        // 작성자도 철회된 방 강도로는 볼 수 없다
        mockMvc.perform(get(url(post)).param("room_id", spicyRoom.toString()).with(as(author)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("10 §8·§13 작업 8 저장 뒤 post epoch 증가 → 과거 문구 대신 템플릿 view, sentencingReason null")
    void epochBumpFallsBackToTemplate() throws Exception {
        aiReadyGuilty();
        f.vote(post, mildMember, mildRoom, "guilty");
        f.vote(post, spicyMember, spicyRoom, "guilty");
        epochs.bump(ScopeKeys.post(post));

        mockMvc.perform(get(url(post)).with(as(author)))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"schemaVersion": 1, "postId": "%s", "juryStatus": "guilty", "sentenceStatus": "FINAL",
                         "textStatus": "AI_READY", "textVersion": 2,
                         "view": {"intensity": "mild", "headline": "유죄", "statement": ["%s"],
                                  "sentence": "oneDay", "sentenceLabel": "징역 1일 (내일 하루 무지출)",
                                  "sentencingReason": null, "source": "TEMPLATE", "meme": null},
                         "pollAfterMs": 0}
                        """.formatted(post, GUILTY_TEMPLATE_LINE_2_OF_2), JsonCompareMode.STRICT));
    }

    @Test
    @DisplayName("10 §8 게시물 삭제 뒤 → 작성자·멤버 모두 404")
    void deletedPostIs404() throws Exception {
        aiReadyGuilty();
        jdbc.update("UPDATE posts SET deleted_at = now() WHERE id = ?", post);

        mockMvc.perform(get(url(post)).with(as(author)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(url(post)).with(as(mildMember)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("10 §9 verdict-view-v1 필수 키 8개(camelCase)가 정확히 있고 view 키도 8개")
    void responseHasSchemaKeys() throws Exception {
        aiReadyGuilty();

        String body = mockMvc.perform(get(url(post)).with(as(author)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode root = JSON.readTree(body);

        // AI 저장소 contracts/verdict-view-v1.schema.json required 를 camelCase 로(사용자 9/15)
        assertThat(keys(root)).isEqualTo(Set.of("schemaVersion", "postId", "juryStatus", "sentenceStatus",
                "textStatus", "textVersion", "view", "pollAfterMs"));
        assertThat(keys(root.get("view"))).isEqualTo(Set.of("intensity", "headline", "statement", "sentence",
                "sentenceLabel", "sentencingReason", "source", "meme"));
    }

    @Test
    @DisplayName("10 §3 각하(dismissed) → juryStatus dismissed·view null·pollAfterMs 0")
    void dismissedView() throws Exception {
        f.verdict(post, "dismissed", "PENDING", null, null, "PENDING", 0, "mild");

        mockMvc.perform(get(url(post)).with(as(author)))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"schemaVersion": 1, "postId": "%s", "juryStatus": "dismissed", "sentenceStatus": "PENDING",
                         "textStatus": "PENDING", "textVersion": 0, "view": null, "pollAfterMs": 0}
                        """.formatted(post), JsonCompareMode.STRICT));
    }

    @Test
    @DisplayName("10 §9 무죄 → view.sentence·sentenceLabel null")
    void notGuiltyHasNoSentence() throws Exception {
        UUID verdict = f.verdict(post, "notGuilty", "FINAL", null, null, "AI_READY", 1, "mild");
        f.text(verdict, "mild", "무죄 헤드라인", List.of("무죄 문장"), "AI", 1, f.snapshot(post, 0));

        mockMvc.perform(get(url(post)).with(as(author)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.juryStatus").value("notGuilty"))
                .andExpect(jsonPath("$.view.sentence").value((Object) null))
                .andExpect(jsonPath("$.view.sentenceLabel").value((Object) null))
                .andExpect(jsonPath("$.view.sentencingReason").value((Object) null))
                .andExpect(jsonPath("$.view.source").value("AI"));
    }

    @Test
    @DisplayName("JWT 없음 → 401")
    void missingJwtIs401() throws Exception {
        mockMvc.perform(get(url(post)))
                .andExpect(status().isUnauthorized());
    }

    private static Set<String> keys(JsonNode node) {
        return new HashSet<>(node.propertyNames().stream().collect(Collectors.toSet()));
    }

    /** 재판 공개 API 테스트 공용 픽스처. 행은 JdbcTemplate 으로 직접 넣는다(privacy InvalidationServiceTest 선례) */
    static final class Fixtures {

        private final JdbcTemplate jdbc;

        Fixtures(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        UUID profile() {
            UUID id = UUID.randomUUID();
            jdbc.update("INSERT INTO profiles (id, nickname, monthly_budget) VALUES (?, 'n', 0)", id);
            return id;
        }

        UUID room(UUID createdBy, String spiceLevel) {
            return jdbc.queryForObject("""
                    INSERT INTO rooms (name, spice_level, vote_deadline_minutes, created_by)
                    VALUES ('room', CAST(? AS spice_level), 30, ?) RETURNING id""", UUID.class, spiceLevel, createdBy);
        }

        void member(UUID room, UUID user) {
            jdbc.update("INSERT INTO room_members (room_id, user_id) VALUES (?, ?)", room, user);
        }

        /** @param deadlineSql vote_deadline_at SQL 식 */
        UUID post(UUID author, String postType, String deadlineSql) {
            return jdbc.queryForObject("""
                    INSERT INTO posts (author_id, post_type, amount_krw, category, item, reason, intake_status,
                                       intake_source, vote_deadline_at)
                    VALUES (?, CAST(? AS post_type), 98765, '식비', '비밀마라탕', '야근했음', 'PASS', 'AI', %s)
                    RETURNING id""".formatted(deadlineSql), UUID.class, author, postType);
        }

        void share(UUID post, UUID room) {
            jdbc.update("INSERT INTO post_rooms (post_id, room_id) VALUES (?, ?)", post, room);
        }

        void revoke(UUID post, UUID room) {
            jdbc.update("UPDATE post_rooms SET revoked_at = now() WHERE post_id = ? AND room_id = ?", post, room);
        }

        UUID verdict(UUID post, String juryResult, String sentenceStatus, String sentence, String sentencingReason,
                     String textStatus, long textVersion, String appliedIntensity) {
            return jdbc.queryForObject("""
                    INSERT INTO verdicts (post_id, jury_result, policy_snapshot, confirmed_at, target_intensities,
                                          default_intensity, applied_intensity, sentence_status, sentence,
                                          sentence_source, sentencing_reason, text_status, text_version)
                    VALUES (?, CAST(? AS verdict), '{}'::jsonb, now(), '["mild","spicy"]'::jsonb, 'mild',
                            CAST(? AS spice_level), ?, CAST(? AS sentence), ?, ?, ?, ?) RETURNING id""",
                    UUID.class, post, juryResult, appliedIntensity, sentenceStatus, sentence,
                    sentence == null ? null : "AI", sentencingReason, textStatus, textVersion);
        }

        /** JuryScheduler 게이트는 SENTENCE dedupe 키가 있으면 넘어간다. 끝난 상태라 reaper·워커도 건드리지 않는다 */
        void blockSentenceGate(UUID verdict) {
            jdbc.update("""
                    INSERT INTO ai.jobs (id, event_id, event_type, kind, dedupe_key, aggregate_id, aggregate_version,
                                         payload, status, priority, attempts, max_attempts, trace_id)
                    VALUES (gen_random_uuid(), gen_random_uuid(), 'test.event', 'SENTENCE', ?, ?, 1,
                            '{}'::jsonb, 'CANCELLED', 10, 0, 2, 'trace')""",
                    "sentence:" + verdict + ":1", verdict.toString());
        }

        String snapshot(UUID post, long epoch) {
            return "[{\"scope_key\": \"" + ScopeKeys.post(post) + "\", \"epoch\": " + epoch + "}]";
        }

        void text(UUID verdict, String intensity, String headline, List<String> sentences, String source,
                  long textVersion, String snapshotJson) {
            String statement = sentences.stream()
                    .map(s -> "{\"text\": \"" + s + "\", \"kind\": \"opinion\", \"evidence_labels\": []}")
                    .collect(Collectors.joining(", ", "[", "]"));
            jdbc.update("""
                    INSERT INTO verdict_texts (verdict_id, intensity, headline, statement, source, text_version,
                                               privacy_epoch_snapshot)
                    VALUES (?, CAST(? AS spice_level), ?, CAST(? AS jsonb), ?, ?, CAST(? AS jsonb))""",
                    verdict, intensity, headline, statement, source, textVersion, snapshotJson);
        }

        UUID meme(String tag, String imageUrl) {
            return jdbc.queryForObject("INSERT INTO meme_images (tag, image_url) VALUES (?, ?) RETURNING id",
                    UUID.class, tag, imageUrl);
        }

        void attachMeme(UUID verdict, UUID meme) {
            jdbc.update("UPDATE verdicts SET meme_image_id = ? WHERE id = ?", meme, verdict);
        }

        void vote(UUID post, UUID voter, UUID room, String verdict) {
            jdbc.update("""
                    INSERT INTO votes (post_id, voter_id, room_id, verdict, reason)
                    VALUES (?, ?, ?, CAST(? AS verdict), '투표사유비밀')""", post, voter, room, verdict);
        }

        /** scope visibility 가 visibilityOrNull 인 근거 하나(없으면 빈 scope) */
        UUID evidence(UUID post, String label, String visibilityOrNull, String text) {
            UUID dossier = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO ai.dossiers (id, post_id, snapshot_hash, label_map, privacy_versions)
                    VALUES (?, ?, 'h', '{}'::jsonb, '[]'::jsonb)""", dossier, post.toString());
            UUID evidence = UUID.randomUUID();
            String scope = visibilityOrNull == null ? "{}" : "{\"visibility\": \"" + visibilityOrNull + "\", \"room_ids\": []}";
            jdbc.update("""
                    INSERT INTO ai.evidence (id, dossier_id, label, epistemic_type, fact_type, text, scope)
                    VALUES (?, ?, ?, 'DB_RECORD', 'SPEND', ?, CAST(? AS jsonb))""", evidence, dossier, label, text, scope);
            return evidence;
        }

        void ref(UUID verdict, long textVersion, String intensity, String fieldPath, UUID evidence) {
            jdbc.update("""
                    INSERT INTO ai.text_evidence_refs (verdict_id, text_version, intensity, field_path, evidence_id)
                    VALUES (?, ?, ?, ?, ?)""", verdict, textVersion, intensity, fieldPath, evidence);
        }

        UUID job(String kind, String status, String payloadJson) {
            UUID id = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO ai.jobs (id, event_id, event_type, kind, dedupe_key, aggregate_id, aggregate_version,
                                         payload, status, priority, attempts, max_attempts, trace_id)
                    VALUES (?, gen_random_uuid(), 'test.event', ?, ?, 'agg', 1, CAST(? AS jsonb), ?, 10, 0, 2, 'trace')""",
                    id, kind, "test:" + id, payloadJson, status);
            return id;
        }

        String jobStatus(UUID job) {
            return jdbc.queryForObject("SELECT status FROM ai.jobs WHERE id = ?", String.class, job);
        }
    }
}
