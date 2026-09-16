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
 * 거지력 랭킹. 배치가 채우던 room_members.debt_score 대신 조회 때 posts 로 계산한다.
 * 점수 = 예산 점수(0~50) + 평결 점수(0~10).
 */
@SpringBootTest
@AutoConfigureMockMvc
class RoomMemberRankingTest extends PostgresContainerSupport {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private Fixtures f;
    private UUID saver;
    private UUID spender;
    private UUID room;

    @BeforeEach
    void seed() {
        f = new Fixtures(jdbc);
        saver = f.profile();
        spender = f.profile();
        room = f.room(saver, "spicy");
        f.member(room, saver);
        f.member(room, spender);
        budget(saver, 1_000_000);
        budget(spender, 1_000_000);
    }

    private void budget(UUID user, int amount) {
        jdbc.update("UPDATE profiles SET monthly_budget = ? WHERE id = ?", amount, user);
    }

    private UUID spentPost(UUID author, int amount) {
        UUID post = jdbc.queryForObject("""
                INSERT INTO posts (author_id, post_type, amount_krw, category, item, intake_status,
                                   intake_source, vote_deadline_at)
                VALUES (?, 'spent', ?, '식비', '점심', 'PASS', 'AI', now() + interval '30 minutes')
                RETURNING id""", UUID.class, author, amount);
        f.share(post, room);
        return post;
    }

    @Test
    @DisplayName("예산을 안 정한 멤버(0원)는 점수가 없다. 집계 전으로 맨 뒤에 간다")
    void noBudgetHasNoScore() throws Exception {
        budget(spender, 0);

        mockMvc.perform(get("/api/rooms/" + room + "/members").with(as(saver)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].userId").value(saver.toString()))
                .andExpect(jsonPath("$[1].userId").value(spender.toString()))
                .andExpect(jsonPath("$[1].debtScore").doesNotExist());
    }

    @Test
    @DisplayName("이번 달 지출이 없으면 예산 점수 만점 + 평결 중립 5점 = 55점")
    void noSpendingScoresFull() throws Exception {
        mockMvc.perform(get("/api/rooms/" + room + "/members").with(as(saver)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].debtScore").value(55));
    }

    @Test
    @DisplayName("많이 쓰면 예산 점수가 깎여 덜 쓴 사람보다 뒤로 간다")
    void bigSpenderRanksLower() throws Exception {
        spentPost(spender, 900_000);

        mockMvc.perform(get("/api/rooms/" + room + "/members").with(as(saver)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].userId").value(saver.toString()))
                .andExpect(jsonPath("$[1].userId").value(spender.toString()));
    }

    @Test
    @DisplayName("무죄를 받으면 평결 점수가 중립 5점보다 오른다")
    void acquittalRaisesScore() throws Exception {
        UUID post = spentPost(saver, 1);
        f.verdict(post, "notGuilty", "FINAL", null, null, "AI_READY", 1, "spicy");

        mockMvc.perform(get("/api/rooms/" + room + "/members").with(as(saver)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].userId").value(saver.toString()))
                .andExpect(jsonPath("$[0].debtScore").value(60));
    }

    @Test
    @DisplayName("유죄만 받으면 평결 점수가 0 이라 중립보다 낮다")
    void convictionLowersScore() throws Exception {
        UUID post = spentPost(saver, 1);
        f.verdict(post, "guilty", "FINAL", "oneDay", "이유", "AI_READY", 1, "spicy");

        mockMvc.perform(get("/api/rooms/" + room + "/members").with(as(saver)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].userId").value(spender.toString()))
                .andExpect(jsonPath("$[1].userId").value(saver.toString()))
                .andExpect(jsonPath("$[1].debtScore").value(50));
    }

    @Test
    @DisplayName("살까 말까는 지출이 아니라 예산 점수를 깎지 않는다")
    void consideringDoesNotCountAsSpending() throws Exception {
        UUID post = jdbc.queryForObject("""
                INSERT INTO posts (author_id, post_type, amount_krw, category, item, intake_status,
                                   intake_source, vote_deadline_at)
                VALUES (?, 'considering', 900000, '쇼핑/패션', '노트북', 'PASS', 'AI', now() + interval '30 minutes')
                RETURNING id""", UUID.class, spender);
        f.share(post, room);

        mockMvc.perform(get("/api/rooms/" + room + "/members").with(as(saver)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].debtScore").value(55))
                .andExpect(jsonPath("$[1].debtScore").value(55));
    }

    @Test
    @DisplayName("삭제된 게시물은 점수에 넣지 않는다")
    void deletedPostIsIgnored() throws Exception {
        UUID post = spentPost(spender, 900_000);
        jdbc.update("UPDATE posts SET deleted_at = now() WHERE id = ?", post);

        mockMvc.perform(get("/api/rooms/" + room + "/members").with(as(saver)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].debtScore").value(55))
                .andExpect(jsonPath("$[1].debtScore").value(55));
    }
}
