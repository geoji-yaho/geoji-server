package com.ttegeoji.backend.api;

import com.ttegeoji.backend.api.PostVerdictControllerTest.Fixtures;
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
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static com.ttegeoji.backend.api.PostVerdictControllerTest.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 방 탈퇴(DELETE /api/rooms/{roomId}/members/me)
@SpringBootTest
@AutoConfigureMockMvc
class RoomMemberLeaveControllerTest extends PostgresContainerSupport {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private Fixtures f;
    private UUID owner;
    private UUID room;

    @BeforeEach
    void seed() {
        f = new Fixtures(jdbc);
        owner = f.profile();
        room = f.room(owner, "mild");
        f.member(room, owner);
    }

    private String url(UUID roomId) {
        return "/api/rooms/" + roomId + "/members/me";
    }

    private boolean isMember(UUID roomId, UUID userId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM room_members WHERE room_id = ? AND user_id = ?", Integer.class, roomId, userId);
        return count != null && count > 0;
    }

    @Test
    @DisplayName("멤버가 탈퇴 → 204, room_members 행 삭제")
    void memberLeaves() throws Exception {
        UUID member = f.profile();
        f.member(room, member);

        mockMvc.perform(delete(url(room)).with(as(member)))
                .andExpect(status().isNoContent());

        assertThat(isMember(room, member)).isFalse();
    }

    @Test
    @DisplayName("방장도 탈퇴 가능 → 방은 삭제되지 않고 그대로 남는다")
    void ownerCanLeaveWithoutDeletingRoom() throws Exception {
        mockMvc.perform(delete(url(room)).with(as(owner)))
                .andExpect(status().isNoContent());

        assertThat(isMember(room, owner)).isFalse();
        Boolean deletedAt = jdbc.queryForObject(
                "SELECT deleted_at IS NOT NULL FROM rooms WHERE id = ?", Boolean.class, room);
        assertThat(deletedAt).isFalse();
    }

    @Test
    @DisplayName("멤버가 아닌 사람이 탈퇴 시도 → 404")
    void nonMemberIs404() throws Exception {
        UUID stranger = f.profile();

        mockMvc.perform(delete(url(room)).with(as(stranger)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    @DisplayName("이미 탈퇴한 방에 다시 탈퇴 시도 → 404")
    void reLeaveIs404() throws Exception {
        UUID member = f.profile();
        f.member(room, member);

        mockMvc.perform(delete(url(room)).with(as(member))).andExpect(status().isNoContent());

        mockMvc.perform(delete(url(room)).with(as(member)))
                .andExpect(status().isNotFound());
    }
}
