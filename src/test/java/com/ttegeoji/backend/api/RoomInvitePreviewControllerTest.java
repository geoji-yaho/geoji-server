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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 초대장 미리보기(GET /api/rooms/invite/{inviteCode}). 참가 전이라 비멤버도 볼 수 있다
@SpringBootTest
@AutoConfigureMockMvc
class RoomInvitePreviewControllerTest extends PostgresContainerSupport {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private Fixtures f;
    private UUID owner;
    private UUID stranger;
    private UUID room;
    private String code;

    @BeforeEach
    void seed() {
        f = new Fixtures(jdbc);
        owner = f.profile();
        stranger = f.profile();
        room = f.room(owner, "hell");
        f.member(room, owner);
        jdbc.update("UPDATE profiles SET nickname = '방장닉' WHERE id = ?", owner);
        jdbc.update("UPDATE rooms SET rules = ARRAY['커피 하루 1잔'] WHERE id = ?", room);
        code = jdbc.queryForObject("SELECT invite_code FROM rooms WHERE id = ?", String.class, room);
    }

    private String url(String inviteCode) {
        return "/api/rooms/invite/" + inviteCode;
    }

    @Test
    @DisplayName("비멤버가 초대 코드로 방 정보를 미리 본다 → 200, alreadyMember=false")
    void strangerSeesPreview() throws Exception {
        mockMvc.perform(get(url(code)).with(as(stranger)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(room.toString()))
                .andExpect(jsonPath("$.name").value("room"))
                .andExpect(jsonPath("$.spiceLevel").value("hell"))
                .andExpect(jsonPath("$.voteDeadlineMinutes").value(30))
                .andExpect(jsonPath("$.rules[0]").value("커피 하루 1잔"))
                .andExpect(jsonPath("$.ownerNickname").value("방장닉"))
                .andExpect(jsonPath("$.memberCount").value(1))
                .andExpect(jsonPath("$.alreadyMember").value(false));
    }

    @Test
    @DisplayName("이미 멤버면 alreadyMember=true")
    void memberSeesAlreadyMember() throws Exception {
        mockMvc.perform(get(url(code)).with(as(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alreadyMember").value(true));
    }

    @Test
    @DisplayName("멤버가 늘면 memberCount 도 는다")
    void memberCountReflectsMembers() throws Exception {
        f.member(room, stranger);

        mockMvc.perform(get(url(code)).with(as(stranger)))
                .andExpect(jsonPath("$.memberCount").value(2));
    }

    @Test
    @DisplayName("없는 초대 코드 → 400, 참가와 같은 문구")
    void unknownCodeIs400() throws Exception {
        mockMvc.perform(get(url("deadbeef")).with(as(stranger)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("유효하지 않은 초대 코드입니다."));
    }

    @Test
    @DisplayName("삭제된 방의 초대 코드 → 400")
    void deletedRoomIs400() throws Exception {
        mockMvc.perform(delete("/api/rooms/" + room).with(as(owner))).andExpect(status().isNoContent());

        mockMvc.perform(get(url(code)).with(as(stranger)))
                .andExpect(status().isBadRequest());
    }
}
