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

import java.util.UUID;

import static com.ttegeoji.backend.api.PostVerdictControllerTest.as;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class RoomCreateControllerTest extends PostgresContainerSupport {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private UUID owner;

    @BeforeEach
    void seed() {
        owner = new Fixtures(jdbc).profile();
    }

    @Test
    @DisplayName("rules 를 빼고 방 생성 → 201, rules 는 빈 배열")
    void createWithoutRules() throws Exception {
        mockMvc.perform(post("/api/rooms").with(as(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"규칙없는방\",\"spiceLevel\":\"mild\",\"voteDeadlineMinutes\":30}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.rules").isArray())
                .andExpect(jsonPath("$.rules.length()").value(0))
                .andExpect(jsonPath("$.inviteCode").isNotEmpty());
    }

    @Test
    @DisplayName("rules 를 넣고 방 생성 → 201, 그대로 저장")
    void createWithRules() throws Exception {
        mockMvc.perform(post("/api/rooms").with(as(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"규칙있는방\",\"spiceLevel\":\"spicy\",\"voteDeadlineMinutes\":60,\"rules\":[\"배달 금지\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.rules[0]").value("배달 금지"));
    }
}
