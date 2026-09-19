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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 방 상세·멤버 목록은 멤버만 본다(프론트 QA 8, 9/19).
 * 전에는 요청자를 보지 않아 방 id 만 알면 남의 방 초대 코드를 받아 들어갈 수 있었다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RoomAccessGuardTest extends PostgresContainerSupport {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private Fixtures f;
    private UUID owner;
    private UUID outsider;
    private UUID room;

    @BeforeEach
    void seed() {
        f = new Fixtures(jdbc);
        owner = f.profile();
        outsider = f.profile();
        room = f.room(owner, "mild");
        f.member(room, owner);
    }

    @Test
    @DisplayName("멤버는 방 상세를 본다 — 초대 코드도 같이 온다")
    void memberSeesRoom() throws Exception {
        mockMvc.perform(get("/api/rooms/" + room).with(as(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inviteCode").isNotEmpty());
    }

    @Test
    @DisplayName("QA 8 멤버가 아니면 방 상세가 404 — 초대 코드가 새지 않는다")
    void outsiderCannotReadRoom() throws Exception {
        mockMvc.perform(get("/api/rooms/" + room).with(as(outsider)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("멤버는 멤버 목록을 본다")
    void memberSeesMembers() throws Exception {
        mockMvc.perform(get("/api/rooms/" + room + "/members").with(as(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    @DisplayName("QA 8 멤버가 아니면 멤버 목록이 404 — 닉네임·거지력이 새지 않는다")
    void outsiderCannotListMembers() throws Exception {
        mockMvc.perform(get("/api/rooms/" + room + "/members").with(as(outsider)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("방을 나간 사람은 다시 404 — 나가기 전 보던 화면으로 되돌아와도 열리지 않는다")
    void leftMemberLosesAccess() throws Exception {
        UUID member = f.profile();
        f.member(room, member);
        mockMvc.perform(get("/api/rooms/" + room).with(as(member))).andExpect(status().isOk());

        jdbc.update("DELETE FROM room_members WHERE room_id = ? AND user_id = ?", room, member);

        mockMvc.perform(get("/api/rooms/" + room).with(as(member)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/rooms/" + room + "/members").with(as(member)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("없는 방과 멤버 아닌 방이 같은 404 — 방이 있는지 드러나지 않는다")
    void missingRoomLooksTheSame() throws Exception {
        mockMvc.perform(get("/api/rooms/" + UUID.randomUUID()).with(as(outsider)))
                .andExpect(status().isNotFound());
    }
}
