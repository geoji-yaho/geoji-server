package com.ttegeoji.backend.api;

import com.ttegeoji.backend.api.PostVerdictControllerTest.Fixtures;
import com.ttegeoji.backend.support.PostgresContainerSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.ttegeoji.backend.api.PostVerdictControllerTest.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 커밋되는 컨테이너 DB 를 공유하므로 테스트마다 새 UUID 로 격리한다
@SpringBootTest
@AutoConfigureMockMvc
class PostCommentControllerTest extends PostgresContainerSupport {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<String> FIELDS = List.of("id", "postId", "roomId", "userId", "nickname", "content",
            "createdAt");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private Fixtures f;
    private UUID author;
    private UUID member;
    private UUID outsider;
    private UUID room;
    private UUID post;

    @BeforeEach
    void seed() {
        f = new Fixtures(jdbc);
        author = f.profile();
        member = f.profile();
        outsider = f.profile();
        room = f.room(author, "mild");
        f.member(room, author);
        f.member(room, member);
        post = f.post(author, "spent", "now() + interval '1 hour'");
        f.share(post, room);
    }

    private String url(UUID postId) {
        return "/api/posts/" + postId + "/comments";
    }

    private ResultActions write(UUID postId, UUID userId, Object roomId, String content) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("roomId", roomId == null ? null : roomId.toString());
        body.put("content", content);
        MockHttpServletRequestBuilder request = post(url(postId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(body));
        if (userId != null) {
            request = request.with(as(userId));
        }
        return mockMvc.perform(request);
    }

    private String createdId(UUID userId, String content) throws Exception {
        String body = write(post, userId, room, content).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return (String) JSON.readValue(body, Map.class).get("id");
    }

    private int commentCount(UUID postId) {
        return jdbc.queryForObject("SELECT count(*) FROM post_comments WHERE post_id = ?", Integer.class, postId);
    }

    @Test
    @DisplayName("SPEC 작성 201 — camelCase 7필드, snake 키 없음")
    void createReturns201CamelCase() throws Exception {
        String body = write(post, member, room, "택시 유죄").andExpect(status().isCreated())
                .andExpect(jsonPath("$.postId").value(post.toString()))
                .andExpect(jsonPath("$.roomId").value(room.toString()))
                .andExpect(jsonPath("$.userId").value(member.toString()))
                .andExpect(jsonPath("$.nickname").value("n"))
                .andExpect(jsonPath("$.content").value("택시 유죄"))
                .andReturn().getResponse().getContentAsString();

        Map<String, Object> json = JSON.readValue(body, Map.class);
        assertThat(json.keySet()).containsExactlyInAnyOrderElementsOf(FIELDS);
        assertThat(body).doesNotContain("post_id", "room_id", "user_id", "created_at");
    }

    @Test
    @DisplayName("SPEC 목록 200 — 삭제 제외·오래된 순·camelCase. 삭제 204·재삭제 204")
    void listAndDelete() throws Exception {
        String first = createdId(member, "첫째");
        String second = createdId(author, "둘째");
        String third = createdId(member, "셋째");

        mockMvc.perform(delete(url(post) + "/" + second).with(as(author)))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete(url(post) + "/" + second).with(as(author)))
                .andExpect(status().isNoContent());

        String body = mockMvc.perform(get(url(post)).with(as(member)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(first))
                .andExpect(jsonPath("$[1].id").value(third))
                .andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> items = JSON.readValue(body, List.class);
        assertThat(items.getFirst().keySet()).containsExactlyInAnyOrderElementsOf(FIELDS);
        assertThat(body).doesNotContain("post_id", "room_id", "user_id", "created_at");
    }

    @Test
    @DisplayName("SPEC 없는·삭제 게시물 → 404 {message}(목록·작성·삭제), 볼 수 없는 사람 목록·작성 404")
    void notFound() throws Exception {
        String comment = createdId(member, "있음");
        UUID missing = UUID.randomUUID();

        mockMvc.perform(get(url(missing)).with(as(member)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("게시물을 찾을 수 없습니다."));
        write(missing, member, room, "없는 게시물")
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").exists());
        mockMvc.perform(get(url(post)).with(as(outsider)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").exists());
        write(post, outsider, room, "남의 게시물")
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").exists());
        mockMvc.perform(delete(url(post) + "/" + UUID.randomUUID()).with(as(member)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("댓글을 찾을 수 없습니다."));

        f.revoke(post, room);
        UUID otherRoom = f.room(author, "mild");
        f.member(otherRoom, member);
        f.share(post, otherRoom);
        jdbc.update("UPDATE posts SET deleted_at = now() WHERE id = ?", post);

        mockMvc.perform(get(url(post)).with(as(member)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").exists());
        write(post, member, otherRoom, "삭제된 게시물")
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").exists());
        mockMvc.perform(delete(url(post) + "/" + comment).with(as(member)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").exists());
    }

    @Test
    @DisplayName("SPEC 지정 방 멤버 아님 → 403 {message}, 타인 댓글 삭제 → 403 {message}")
    void forbidden() throws Exception {
        UUID notShared = f.room(member, "mild");
        f.member(notShared, member);

        write(post, member, notShared, "공유 안 된 방")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("이 방에서는 댓글을 달 수 없습니다."));

        String comment = createdId(member, "내 댓글");
        mockMvc.perform(delete(url(post) + "/" + comment).with(as(author)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("본인 댓글만 삭제할 수 있습니다."));
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NULL FROM post_comments WHERE id = CAST(? AS uuid)",
                Boolean.class, comment)).isTrue();
    }

    @Test
    @DisplayName("SPEC 검증 400 {message} — 201자·빈 값·공백만·roomId 누락·roomId UUID 아님. 행 없음")
    void validation() throws Exception {
        write(post, member, room, "가".repeat(201))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("댓글은 200자 이하여야 합니다."));
        write(post, member, room, "")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("댓글 내용을 입력해 주세요."));
        write(post, member, room, "   ")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").exists());
        write(post, member, null, "방 없음")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("댓글을 달 방을 지정해 주세요."));
        write(post, member, "not-a-uuid", "방 이상함")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").exists());

        assertThat(commentCount(post)).isZero();
    }

    @Test
    @DisplayName("SPEC JWT 없음 → 401, 행 없음")
    void unauthorized() throws Exception {
        write(post, null, room, "익명").andExpect(status().isUnauthorized());
        mockMvc.perform(get(url(post))).andExpect(status().isUnauthorized());
        String comment = createdId(member, "지우려는 댓글");
        mockMvc.perform(delete(url(post) + "/" + comment)).andExpect(status().isUnauthorized());

        assertThat(commentCount(post)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NULL FROM post_comments WHERE id = CAST(? AS uuid)",
                Boolean.class, comment)).isTrue();
    }

    @Test
    @DisplayName("room_id 를 주면 그 방 댓글만. 작성자도 다른 방 댓글은 못 본다")
    void roomScopedListHidesOtherRooms() throws Exception {
        UUID otherRoom = f.room(author, "hell");
        f.member(otherRoom, author);
        UUID otherMember = f.profile();
        f.member(otherRoom, otherMember);
        f.share(post, otherRoom);
        write(post, member, room, "이 방 댓글").andExpect(status().isCreated());
        write(post, otherMember, otherRoom, "딴 방 댓글").andExpect(status().isCreated());

        mockMvc.perform(get(url(post) + "?room_id=" + room).with(as(author)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].content").value("이 방 댓글"));

        mockMvc.perform(get(url(post) + "?room_id=" + otherRoom).with(as(author)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].content").value("딴 방 댓글"));
    }

    @Test
    @DisplayName("멤버가 아닌 방의 room_id 로 조회하면 404")
    void roomScopedListRejectsNonMember() throws Exception {
        UUID otherRoom = f.room(author, "hell");
        f.member(otherRoom, author);
        f.share(post, otherRoom);

        mockMvc.perform(get(url(post) + "?room_id=" + otherRoom).with(as(member)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("9/16 A 방만 판결이 확정돼도 B 방 댓글은 RETAIN 에 들어가지 않는다")
    void retainFollowsTheRoomVerdict() throws Exception {
        UUID otherRoom = f.room(author, "hell");
        f.member(otherRoom, author);
        UUID otherMember = f.profile();
        f.member(otherRoom, otherMember);
        f.share(post, otherRoom);
        // 이 방(room)만 판결 확정
        jdbc.update("""
                INSERT INTO verdicts (post_id, room_id, jury_result, policy_snapshot, confirmed_at,
                                      target_intensities, default_intensity, sentence_status)
                VALUES (?, ?, 'guilty', '{}'::jsonb, now(), '["mild"]'::jsonb, 'mild', 'FINAL')""", post, room);

        String judged = idOf(write(post, member, room, "확정된 방 댓글"));
        String notJudged = idOf(write(post, otherMember, otherRoom, "아직 투표 중인 방 댓글"));

        assertThat(retained(judged)).isTrue();
        assertThat(retained(notJudged)).isFalse();
    }

    private String idOf(org.springframework.test.web.servlet.ResultActions created) throws Exception {
        String body = created.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return (String) JSON.readValue(body, Map.class).get("id");
    }

    private boolean retained(String commentId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT retained_at IS NOT NULL FROM post_comments WHERE id = ?", Boolean.class,
                UUID.fromString(commentId)));
    }
}
