package com.ttegeoji.backend.api;

import com.ttegeoji.backend.api.PostVerdictControllerTest.Fixtures;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.ttegeoji.backend.api.PostVerdictControllerTest.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 커밋되는 컨테이너 DB 를 공유하므로 테스트마다 새 UUID 로 격리한다
@SpringBootTest
@AutoConfigureMockMvc
class ShareCardControllerTest extends PostgresContainerSupport {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String TEMPLATE_LINE = "배심원단이 이 지출을 유죄로 판단했습니다.";
    private static final String PRIVATE_EVIDENCE_TEXT = "비공개근거원문문장";

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
    private UUID member;
    private UUID room;
    private UUID post;

    @BeforeEach
    void seed() {
        f = new Fixtures(jdbc);
        author = f.profile();
        member = f.profile();
        room = f.room(author, "spicy");
        f.member(room, author);
        f.member(room, member);
        post = f.post(author, "spent", "now() + interval '1 hour'");
        f.share(post, room);
    }

    private String url(UUID postId) {
        return "/api/posts/" + postId + "/share-card";
    }

    /** 유죄 FINAL·AI_READY, applied mild 행에 AI 문장 2개 */
    private UUID aiVerdict() {
        UUID verdict = f.verdict(post, "guilty", "FINAL", "oneDay", "AI 양형 이유 비공개", "AI_READY", 3, "mild");
        f.text(verdict, "mild", "AI 헤드라인", List.of("AI 공개 문장", "AI 둘째 문장"), "AI", 3, f.snapshot(post, 0));
        return verdict;
    }

    @Test
    @DisplayName("10 §9 인용 근거가 전부 PUBLIC → AI 문장·AI 헤드라인 그대로")
    void allPublicKeepsAi() throws Exception {
        UUID verdict = aiVerdict();
        f.ref(verdict, 3, "mild", "statement[0]", f.evidence(post, "F0", "PUBLIC", "공개 근거"));
        f.ref(verdict, 3, "mild", "statement[1]", f.evidence(post, "F1", "PUBLIC", "공개 근거 2"));
        UUID meme = f.meme("GUILTY_LIGHT", "https://cdn.example/share.png");
        f.attachMeme(verdict, meme);

        mockMvc.perform(get(url(post)).with(as(member)))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"postId": "%s", "postType": "spent", "juryStatus": "guilty", "intensity": "mild",
                         "headline": "AI 헤드라인", "statement": ["AI 공개 문장", "AI 둘째 문장"],
                         "sentence": "oneDay", "sentenceLabel": "징역 1일 (내일 하루 무지출)",
                         "meme": {"tag": "GUILTY_LIGHT", "imageId": "%s", "imageUrl": "https://cdn.example/share.png"}}
                        """.formatted(post, meme), JsonCompareMode.STRICT));
    }

    @Test
    @DisplayName("9/19 ROOMS 근거를 인용해도 AI 문장·AI 헤드라인 그대로 — 카드가 방 규칙을 품는다")
    void roomsEvidenceKeepsAi() throws Exception {
        UUID verdict = aiVerdict();
        f.ref(verdict, 3, "mild", "statement[0]", f.evidence(post, "F0", "PUBLIC", "공개 근거"));
        f.ref(verdict, 3, "mild", "statement[1]", f.evidence(post, "F2", "ROOMS", PRIVATE_EVIDENCE_TEXT));

        mockMvc.perform(get(url(post)).with(as(author)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.headline").value("AI 헤드라인"))
                .andExpect(jsonPath("$.statement[0]").value("AI 공개 문장"))
                .andExpect(jsonPath("$.statement[1]").value("AI 둘째 문장"));
    }

    @Test
    @DisplayName("9/19 PRIVATE·scope 없음·무효화된 근거를 인용해도 AI 문장 그대로")
    void nonPublicVariantsKeepAi() throws Exception {
        UUID verdict = aiVerdict();
        f.ref(verdict, 3, "mild", "statement[0]", f.evidence(post, "F0", null, "scope 없음"));
        UUID invalidated = f.evidence(post, "F1", "PUBLIC", "무효화됨");
        jdbc.update("UPDATE ai.evidence SET invalidated_at = now() WHERE id = ?", invalidated);
        f.ref(verdict, 3, "mild", "statement[1]", invalidated);

        mockMvc.perform(get(url(post)).with(as(author)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.headline").value("AI 헤드라인"))
                .andExpect(jsonPath("$.statement[0]").value("AI 공개 문장"))
                .andExpect(jsonPath("$.statement[1]").value("AI 둘째 문장"));
    }

    @Test
    @DisplayName("10 §9 인용이 없는 AI 문장은 공개 가능 → AI 그대로")
    void noRefsKeepsAi() throws Exception {
        aiVerdict();

        mockMvc.perform(get(url(post)).with(as(author)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.headline").value("AI 헤드라인"))
                .andExpect(jsonPath("$.statement[0]").value("AI 공개 문장"))
                .andExpect(jsonPath("$.statement[1]").value("AI 둘째 문장"));
    }

    @Test
    @DisplayName("10 §8 저장 뒤 epoch 불일치 → 전부 템플릿(공유 카드 캐시도 버전이 달라짐)")
    void epochMismatchUsesTemplate() throws Exception {
        UUID verdict = aiVerdict();
        f.ref(verdict, 3, "mild", "statement[0]", f.evidence(post, "F0", "PUBLIC", "공개 근거"));
        epochs.bump(ScopeKeys.post(post));

        mockMvc.perform(get(url(post)).with(as(author)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.headline").value("유죄"))
                .andExpect(jsonPath("$.statement.length()").value(1))
                .andExpect(jsonPath("$.statement[0]").value(TEMPLATE_LINE));
    }

    @Test
    @DisplayName("10 §9 응답 키는 공개 필드 9개뿐 — 금액·item·사유·투표 사유·근거 원문·양형 이유·source 없음")
    void noPrivateFields() throws Exception {
        UUID verdict = aiVerdict();
        f.vote(post, member, room, "guilty");
        f.ref(verdict, 3, "mild", "statement[1]", f.evidence(post, "F0", "ROOMS", PRIVATE_EVIDENCE_TEXT));

        String body = mockMvc.perform(get(url(post)).with(as(author)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode root = JSON.readTree(body);
        assertThat(root.propertyNames().stream().collect(Collectors.toSet())).isEqualTo(Set.of(
                "postId", "postType", "juryStatus", "intensity", "headline", "statement", "sentence", "sentenceLabel",
                "meme"));
        // 근거 원문·금액·item·투표 사유·양형 이유는 카드에 없다. AI 문장 자체는 9/19 부터 그대로 나간다
        assertThat(body).doesNotContain(PRIVATE_EVIDENCE_TEXT, "비밀마라탕", "야근했음", "투표사유비밀",
                "AI 양형 이유 비공개");
    }

    @Test
    @DisplayName("10 §9 판결 확정 전(verdicts 없음·sentence PENDING·각하) → 404")
    void notConfirmedIs404() throws Exception {
        mockMvc.perform(get(url(post)).with(as(author)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").exists());

        UUID verdict = f.verdict(post, "guilty", "PENDING", null, null, "PENDING", 0, "mild");
        f.blockSentenceGate(verdict);
        mockMvc.perform(get(url(post)).with(as(author)))
                .andExpect(status().isNotFound());

        UUID dismissedPost = f.post(author, "spent", "now() + interval '1 hour'");
        f.share(dismissedPost, room);
        f.verdict(dismissedPost, "dismissed", "PENDING", null, null, "PENDING", 0, "mild");
        mockMvc.perform(get(url(dismissedPost)).with(as(author)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("10 §9 공유 방 멤버 아님·철회된 방 멤버·삭제된 게시물 → 404")
    void nonMemberIs404() throws Exception {
        aiVerdict();
        UUID outsider = f.profile();
        mockMvc.perform(get(url(post)).with(as(outsider)))
                .andExpect(status().isNotFound());

        f.revoke(post, room);
        mockMvc.perform(get(url(post)).with(as(member)))
                .andExpect(status().isNotFound());

        jdbc.update("UPDATE posts SET deleted_at = now() WHERE id = ?", post);
        mockMvc.perform(get(url(post)).with(as(author)))
                .andExpect(status().isNotFound());
    }
}
