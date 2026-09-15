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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 방 삭제(DELETE /api/rooms/{roomId}). 소프트 삭제 — deleted_at 만 찍는다.
@SpringBootTest
@AutoConfigureMockMvc
class RoomDeleteControllerTest extends PostgresContainerSupport {

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
        return "/api/rooms/" + roomId;
    }

    private boolean deleted(UUID roomId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT deleted_at IS NOT NULL FROM rooms WHERE id = ?", Boolean.class, roomId));
    }

    @Test
    @DisplayName("방장 삭제 → 204, deleted_at 표시")
    void ownerDeletes() throws Exception {
        mockMvc.perform(delete(url(room)).with(as(owner)))
                .andExpect(status().isNoContent());
        assertThat(deleted(room)).isTrue();
    }

    @Test
    @DisplayName("방장이 아닌 멤버 → 403, 삭제되지 않음")
    void memberIsForbidden() throws Exception {
        UUID other = f.profile();
        f.member(room, other);

        mockMvc.perform(delete(url(room)).with(as(other)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").exists());
        assertThat(deleted(room)).isFalse();
    }

    @Test
    @DisplayName("존재하지 않는 방 → 404")
    void missingRoomIs404() throws Exception {
        mockMvc.perform(delete(url(UUID.randomUUID())).with(as(owner)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("삭제된 방을 다시 삭제 → 404(목록·조회에서 이미 숨겨짐)")
    void redeleteIs404() throws Exception {
        mockMvc.perform(delete(url(room)).with(as(owner))).andExpect(status().isNoContent());

        mockMvc.perform(delete(url(room)).with(as(owner)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("삭제된 방은 단건 조회에서도 404")
    void deletedRoomHiddenFromGet() throws Exception {
        mockMvc.perform(delete(url(room)).with(as(owner))).andExpect(status().isNoContent());

        mockMvc.perform(get(url(room)).with(as(owner)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("삭제된 방은 초대 코드로 참가할 수 없음")
    void deletedRoomRejectsJoin() throws Exception {
        String inviteCode = jdbc.queryForObject("SELECT invite_code FROM rooms WHERE id = ?", String.class, room);
        mockMvc.perform(delete(url(room)).with(as(owner))).andExpect(status().isNoContent());

        UUID joiner = f.profile();
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/rooms/join/" + inviteCode).with(as(joiner)))
                .andExpect(status().isBadRequest());
    }
}
