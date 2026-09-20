package com.ttegeoji.backend.api;

import com.ttegeoji.backend.ai.IntakeClient;
import com.ttegeoji.backend.ai.IntakeClient.CategoryReview;
import com.ttegeoji.backend.ai.IntakeClient.IntakeResult;
import com.ttegeoji.backend.ai.IntakeClient.ItemReview;
import com.ttegeoji.backend.ai.IntakeClient.Mode;
import com.ttegeoji.backend.ai.IntakeClient.Status;
import com.ttegeoji.backend.api.PostVerdictControllerTest.Fixtures;
import com.ttegeoji.backend.domain.enums.IntakeSource;
import com.ttegeoji.backend.support.PostgresContainerSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.UUID;

import static com.ttegeoji.backend.api.PostVerdictControllerTest.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 19 §6 POST /api/rooms/{roomId}/ai-member. IntakeClient 는 목(실제 AI API 를 부르지 않는다).
 * 서비스가 스스로 커밋하므로 테스트마다 새 방·사용자로 격리한다. 봇 id 는 application-test.yml 의 값.
 * 컨텍스트는 SubmissionServiceTest 와 같은 구성(IntakeClient 목만)으로 맞춰 새로 띄우지 않는다.
 */
@SpringBootTest
class RoomAiMemberControllerTest extends PostgresContainerSupport {

    static final UUID AI_JUROR = UUID.fromString("00000000-0000-4000-8000-0000000a1b0c");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private IntakeClient intakeClient;

    private MockMvc mockMvc;

    private Fixtures f;
    private UUID owner;
    private UUID room;

    @BeforeEach
    void seed() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        f = new Fixtures(jdbc);
        jdbc.update("INSERT INTO profiles (id, nickname, monthly_budget) VALUES (?, '떼거지봇', 300000) ON CONFLICT (id) DO NOTHING",
                AI_JUROR);
        owner = f.profile();
        room = f.room(owner, "spicy");
        f.member(room, owner);
        intakeReturns(Mode.INITIAL, Status.PASS);
    }

    private void intakeReturns(Mode mode, Status status) {
        IntakeResult result = new IntakeResult(mode, status, new ItemReview("OK", null),
                status == Status.NEEDS_CLARIFICATION ? "무엇을 샀는지 알려주세요" : null,
                new CategoryReview("OK", null, 1.0), false, IntakeSource.AI);
        when(intakeClient.call(argThat(r -> r != null && r.mode() == mode))).thenReturn(result);
    }

    private ResultActions add(UUID roomId, UUID userId) throws Exception {
        var request = post("/api/rooms/" + roomId + "/ai-member");
        if (userId != null) {
            request = request.with(as(userId));
        }
        return mockMvc.perform(request);
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    private List<UUID> botPosts(UUID roomId) {
        return jdbc.queryForList("""
                SELECT p.id FROM posts p JOIN post_rooms pr ON pr.post_id = p.id
                 WHERE pr.room_id = ? AND p.author_id = ? ORDER BY p.created_at""", UUID.class, roomId, AI_JUROR);
    }

    private static JsonNode json(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("19 §6 정상 → 201 {userId, nickname 떼거지봇, postIds 2(spent·considering 순)}, room_members 1행, 봇 글 2개, PREPARE 2, JURY_VOTE 0")
    void addsBotAndTemplates() throws Exception {
        MvcResult result = add(room, owner)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value(AI_JUROR.toString()))
                .andExpect(jsonPath("$.nickname").value("떼거지봇"))
                .andExpect(jsonPath("$.postIds.length()").value(2))
                .andReturn();

        assertThat(count("SELECT count(*) FROM room_members WHERE room_id = ? AND user_id = ?", room, AI_JUROR)).isEqualTo(1);
        List<UUID> posts = botPosts(room);
        assertThat(posts).hasSize(2);
        JsonNode ids = json(result).get("postIds");
        assertThat(List.of(UUID.fromString(ids.get(0).stringValue()), UUID.fromString(ids.get(1).stringValue())))
                .containsExactlyInAnyOrderElementsOf(posts);

        var spent = jdbc.queryForMap("SELECT post_type::text AS t, amount_krw, category, item, reason FROM posts WHERE id = ?",
                UUID.fromString(ids.get(0).stringValue()));
        assertThat(spent).containsEntry("t", "spent").containsEntry("amount_krw", 32000)
                .containsEntry("category", "교통/택시").containsEntry("item", "심야 택시")
                .containsEntry("reason", "막차가 끊겨서 어쩔 수 없었어요");
        var considering = jdbc.queryForMap("SELECT post_type::text AS t, amount_krw, category, item, reason FROM posts WHERE id = ?",
                UUID.fromString(ids.get(1).stringValue()));
        assertThat(considering).containsEntry("t", "considering").containsEntry("amount_krw", 189000)
                .containsEntry("category", "쇼핑/패션").containsEntry("item", "무선 이어폰")
                .containsEntry("reason", "기존 이어폰 한쪽이 안 들려요");

        for (UUID postId : posts) {
            assertThat(count("SELECT count(*) FROM ai.jobs WHERE kind = 'PREPARE' AND aggregate_id = ?", postId.toString()))
                    .isEqualTo(1);
            // 봇 글에는 봇 표를 만들지 않는다(19 §1)
            assertThat(count("SELECT count(*) FROM ai.jobs WHERE kind = 'JURY_VOTE' AND aggregate_id = ?", postId.toString()))
                    .isZero();
        }
    }

    @Test
    @DisplayName("19 §6·§9 두 번 → 두 번째 200 같은 모양, 멤버 1행·글 2개 그대로")
    void secondCallIsIdempotent() throws Exception {
        JsonNode first = json(add(room, owner).andExpect(status().isCreated()).andReturn());
        JsonNode second = json(add(room, owner).andExpect(status().isOk()).andReturn());

        assertThat(second).isEqualTo(first);
        assertThat(count("SELECT count(*) FROM room_members WHERE room_id = ? AND user_id = ?", room, AI_JUROR)).isEqualTo(1);
        assertThat(botPosts(room)).hasSize(2);
    }

    @Test
    @DisplayName("19 §6 멤버는 있고 글만 없으면 글만 만들고 201 — 부분 상태도 채운다")
    void fillsMissingPostsOnly() throws Exception {
        f.member(room, AI_JUROR);

        add(room, owner).andExpect(status().isCreated());

        assertThat(count("SELECT count(*) FROM room_members WHERE room_id = ? AND user_id = ?", room, AI_JUROR)).isEqualTo(1);
        assertThat(botPosts(room)).hasSize(2);
    }

    @Test
    @DisplayName("19 §7 심문관이 질문(NEEDS_CLARIFICATION)해도 PROCEED 로 등록 — UNCLARIFIED 글 2개")
    void proceedsOnClarification() throws Exception {
        intakeReturns(Mode.INITIAL, Status.NEEDS_CLARIFICATION);

        add(room, owner).andExpect(status().isCreated()).andExpect(jsonPath("$.postIds.length()").value(2));

        assertThat(jdbc.queryForList("SELECT intake_status FROM posts WHERE author_id = ? AND id IN (SELECT post_id FROM post_rooms WHERE room_id = ?)",
                String.class, AI_JUROR, room)).containsOnly("UNCLARIFIED").hasSize(2);
    }

    @Test
    @DisplayName("19 §6 방 없음·요청자가 멤버 아님·삭제된 방 → 404 {message: 방을 찾을 수 없습니다.}, 아무것도 안 만듦")
    void notFoundVariants() throws Exception {
        UUID stranger = f.profile();
        add(room, stranger).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("방을 찾을 수 없습니다."));
        add(UUID.randomUUID(), owner).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("방을 찾을 수 없습니다."));
        jdbc.update("UPDATE rooms SET deleted_at = now() WHERE id = ?", room);
        add(room, owner).andExpect(status().isNotFound());

        assertThat(count("SELECT count(*) FROM room_members WHERE room_id = ? AND user_id = ?", room, AI_JUROR)).isZero();
        assertThat(botPosts(room)).isEmpty();
    }

    @Test
    @DisplayName("19 §6 JWT 없음 → 401")
    void unauthorized() throws Exception {
        add(room, null).andExpect(status().isUnauthorized());
    }
}
