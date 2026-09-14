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
import java.util.Map;
import java.util.UUID;

import static com.ttegeoji.backend.api.PostVerdictControllerTest.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 커밋되는 컨테이너 DB 를 공유하므로 테스트마다 새 UUID 로 격리한다
@SpringBootTest
@AutoConfigureMockMvc
class PostVoteControllerTest extends PostgresContainerSupport {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private Fixtures f;
    private UUID author;
    private UUID voter;
    private UUID otherVoter;
    private UUID room;
    private UUID spentPost;

    @BeforeEach
    void seed() {
        f = new Fixtures(jdbc);
        author = f.profile();
        voter = f.profile();
        otherVoter = f.profile();
        room = f.room(author, "mild");
        f.member(room, author);
        f.member(room, voter);
        f.member(room, otherVoter);
        // 투표 가능 인원 2명이라 한 표로는 평결이 확정되지 않는다
        spentPost = f.post(author, "spent", "now() + interval '1 hour'");
        f.share(spentPost, room);
    }

    private ResultActions vote(UUID postId, UUID userId, String verdict, String reason, UUID roomId) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("verdict", verdict);
        body.put("reason", reason);
        body.put("roomId", roomId == null ? null : roomId.toString());
        MockHttpServletRequestBuilder request = post("/api/posts/" + postId + "/votes")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(body));
        if (userId != null) {
            request = request.with(as(userId));
        }
        return mockMvc.perform(request);
    }

    private int voteCount(UUID postId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM votes WHERE post_id = ?", Integer.class, postId);
        return count == null ? 0 : count;
    }

    private boolean verdictExists(UUID postId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM verdicts WHERE post_id = ?)", Boolean.class, postId));
    }

    @Test
    @DisplayName("SPEC S-14 정상 투표 → 201 {id, postId, roomId, verdict, reason, createdAt}, votes 행 저장, 평결은 아직")
    void castVote() throws Exception {
        vote(spentPost, voter, "guilty", "택시 대신 지하철", room)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.postId").value(spentPost.toString()))
                .andExpect(jsonPath("$.roomId").value(room.toString()))
                .andExpect(jsonPath("$.verdict").value("guilty"))
                .andExpect(jsonPath("$.reason").value("택시 대신 지하철"))
                .andExpect(jsonPath("$.createdAt").isString());

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT voter_id, room_id, verdict::text AS verdict, reason FROM votes WHERE post_id = ?", spentPost);
        assertThat(row).containsEntry("voter_id", voter).containsEntry("room_id", room)
                .containsEntry("verdict", "guilty").containsEntry("reason", "택시 대신 지하철");
        assertThat(verdictExists(spentPost)).isFalse();
    }

    @Test
    @DisplayName("SPEC S-14 작성자 투표 → 403 {message}")
    void authorCannotVote() throws Exception {
        vote(spentPost, author, "guilty", "셀프 재판", room)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").exists());
        assertThat(voteCount(spentPost)).isZero();
    }

    @Test
    @DisplayName("SPEC S-14 post_type 에 안 맞는 verdict → 400 (spent 에 agree·dismissed·null·모르는 값, considering 에 guilty)")
    void verdictMustMatchPostType() throws Exception {
        vote(spentPost, voter, "agree", "사유", room).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
        vote(spentPost, voter, "dismissed", "사유", room).andExpect(status().isBadRequest());
        vote(spentPost, voter, null, "사유", room).andExpect(status().isBadRequest());
        vote(spentPost, voter, "GUILTY", "사유", room).andExpect(status().isBadRequest());

        UUID considering = f.post(author, "considering", "now() + interval '1 hour'");
        f.share(considering, room);
        vote(considering, voter, "guilty", "사유", room).andExpect(status().isBadRequest());
        vote(considering, voter, "agree", "사유", room).andExpect(status().isCreated());

        assertThat(voteCount(spentPost)).isZero();
    }

    @Test
    @DisplayName("SPEC S-14 reason null·빈 값·공백만 → 400")
    void reasonRequired() throws Exception {
        vote(spentPost, voter, "guilty", null, room).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
        vote(spentPost, voter, "guilty", "", room).andExpect(status().isBadRequest());
        vote(spentPost, voter, "guilty", "   ", room).andExpect(status().isBadRequest());
        assertThat(voteCount(spentPost)).isZero();
    }

    @Test
    @DisplayName("SPEC S-14 reason 501자 → 400, 500자 → 201")
    void reasonMax500() throws Exception {
        vote(spentPost, voter, "guilty", "가".repeat(501), room)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
        vote(spentPost, voter, "guilty", "가".repeat(500), room)
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("SPEC S-14 공유 방이 아닌 room_id(요청자는 그 방 멤버) → 403")
    void roomMustBeSharedRoom() throws Exception {
        UUID unshared = f.room(voter, "spicy");
        f.member(unshared, voter);

        vote(spentPost, voter, "guilty", "사유", unshared)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").exists());
        vote(spentPost, voter, "guilty", "사유", null)
                .andExpect(status().isForbidden());
        // 공유 방이지만 요청자가 멤버가 아님
        UUID stranger = f.profile();
        vote(spentPost, stranger, "guilty", "사유", room)
                .andExpect(status().isForbidden());
        assertThat(voteCount(spentPost)).isZero();
    }

    @Test
    @DisplayName("10 §8 공유가 철회된 방의 멤버 → 403")
    void revokedRoomMemberIs403() throws Exception {
        UUID revokedRoom = f.room(author, "hell");
        UUID revokedMember = f.profile();
        f.member(revokedRoom, revokedMember);
        f.share(spentPost, revokedRoom);
        f.revoke(spentPost, revokedRoom);

        vote(spentPost, revokedMember, "guilty", "사유", revokedRoom)
                .andExpect(status().isForbidden());
        assertThat(voteCount(spentPost)).isZero();
    }

    @Test
    @DisplayName("SPEC S-14 1인 1표 — 같은 사람이 다시 투표 → 409, 표는 1개")
    void duplicateVoteIs409() throws Exception {
        vote(spentPost, voter, "guilty", "첫 표", room).andExpect(status().isCreated());

        vote(spentPost, voter, "notGuilty", "바꿀래요", room)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("이미 투표했습니다."));
        assertThat(voteCount(spentPost)).isEqualTo(1);
    }

    @Test
    @DisplayName("SPEC S-14 마감(vote_deadline_at ≤ now) 뒤 → 409")
    void afterDeadlineIs409() throws Exception {
        UUID closed = f.post(author, "spent", "now() - interval '1 minute'");
        f.share(closed, room);

        vote(closed, voter, "guilty", "늦었다", room)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").exists());
        assertThat(voteCount(closed)).isZero();
    }

    @Test
    @DisplayName("10 §3 투표 가능 인원 전원이 투표 → 같은 트랜잭션에서 verdicts 행 생성")
    void lastVoteConfirmsVerdict() throws Exception {
        vote(spentPost, voter, "guilty", "첫 표", room).andExpect(status().isCreated());
        assertThat(verdictExists(spentPost)).isFalse();

        vote(spentPost, otherVoter, "guilty", "둘째 표", room).andExpect(status().isCreated());

        assertThat(verdictExists(spentPost)).isTrue();
        // 평결이 확정된 뒤의 표는 받지 않는다
        UUID lateMember = f.profile();
        f.member(room, lateMember);
        vote(spentPost, lateMember, "notGuilty", "늦은 표", room).andExpect(status().isConflict());
    }

    @Test
    @DisplayName("SPEC S-14 삭제된 게시물·없는 게시물 → 404")
    void deletedPostIs404() throws Exception {
        jdbc.update("UPDATE posts SET deleted_at = now() WHERE id = ?", spentPost);

        vote(spentPost, voter, "guilty", "사유", room)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").exists());
        vote(UUID.randomUUID(), voter, "guilty", "사유", room)
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("JWT 없음 → 401, 표 없음")
    void missingJwtIs401() throws Exception {
        vote(spentPost, null, "guilty", "사유", room)
                .andExpect(status().isUnauthorized());
        assertThat(voteCount(spentPost)).isZero();
    }
}
