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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 지출 재판. 살까 말까(purchase_check)는 동의/기각 투표, 정족수 2표, 형량 없음
@SpringBootTest
@AutoConfigureMockMvc
class ExpenseTrialControllerTest extends PostgresContainerSupport {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private UUID author;
    private UUID juror1;
    private UUID juror2;
    private UUID juror3;
    private UUID room;

    @BeforeEach
    void seed() {
        Fixtures f = new Fixtures(jdbc);
        author = f.profile();
        juror1 = f.profile();
        juror2 = f.profile();
        juror3 = f.profile();
        room = f.room(author, "mild");
        for (UUID u : new UUID[]{author, juror1, juror2, juror3}) {
            f.member(room, u);
        }
    }

    private UUID expense(String source) {
        return jdbc.queryForObject("""
                INSERT INTO expenses (user_id, amount, memo, source, spent_at)
                VALUES (?, 39000, '에어팟 케이스', CAST(? AS expense_source), now()) RETURNING id""",
                UUID.class, author, source);
    }

    private String base(UUID expenseId) {
        return "/api/rooms/" + room + "/expenses/" + expenseId;
    }

    private void vote(UUID expenseId, UUID voter, String verdict, int expectedStatus) throws Exception {
        mockMvc.perform(post(base(expenseId) + "/votes").with(as(voter))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"verdict\":\"" + verdict + "\",\"reason\":\"사유\"}"))
                .andExpect(status().is(expectedStatus));
    }

    @Test
    @DisplayName("살까 말까 재판 조회 → 400 이 아니라 재판 생성")
    void purchaseCheckTrialIsCreated() throws Exception {
        UUID e = expense("purchase_check");
        mockMvc.perform(get(base(e) + "/trial").with(as(juror1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verdict").doesNotExist())
                .andExpect(jsonPath("$.agreeVotes").value(0))
                .andExpect(jsonPath("$.disagreeVotes").value(0));
    }

    @Test
    @DisplayName("살까 말까는 agree·disagree 를 받고 guilty·notGuilty·dismissed 는 400")
    void purchaseCheckAcceptsOnlyAgreeDisagree() throws Exception {
        UUID e = expense("purchase_check");
        vote(e, juror1, "guilty", 400);
        vote(e, juror1, "dismissed", 400);
        vote(e, juror1, "agree", 201);
        vote(e, juror2, "disagree", 201);

        mockMvc.perform(get(base(e) + "/trial").with(as(juror1)))
                .andExpect(jsonPath("$.agreeVotes").value(1))
                .andExpect(jsonPath("$.disagreeVotes").value(1))
                .andExpect(jsonPath("$.guiltyVotes").value(0))
                .andExpect(jsonPath("$.myVote").value("agree"));
    }

    @Test
    @DisplayName("돈 썼어요는 agree·disagree 를 400 으로 거부")
    void quickTapRejectsAgreeDisagree() throws Exception {
        UUID e = expense("quick_tap");
        vote(e, juror1, "agree", 400);
        vote(e, juror1, "guilty", 201);
    }

    @Test
    @DisplayName("살까 말까 판결: 동의 > 기각 → agree, 형량 null")
    void purchaseAgree() throws Exception {
        UUID e = expense("purchase_check");
        vote(e, juror1, "agree", 201);
        vote(e, juror2, "agree", 201);
        vote(e, juror3, "disagree", 201);

        mockMvc.perform(post(base(e) + "/trial/judge").with(as(juror1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verdict").value("agree"))
                .andExpect(jsonPath("$.sentenceDays").doesNotExist())
                .andExpect(jsonPath("$.sentenceStartedAt").doesNotExist())
                .andExpect(jsonPath("$.verdictText").isNotEmpty());
    }

    @Test
    @DisplayName("살까 말까 판결: 동률 → disagree")
    void purchaseTieIsDisagree() throws Exception {
        UUID e = expense("purchase_check");
        vote(e, juror1, "agree", 201);
        vote(e, juror2, "disagree", 201);

        mockMvc.perform(post(base(e) + "/trial/judge").with(as(juror1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verdict").value("disagree"))
                .andExpect(jsonPath("$.sentenceDays").doesNotExist());
    }

    @Test
    @DisplayName("살까 말까 판결: 1표(정족수 2 미달) → dismissed")
    void purchaseUnderQuorumIsDismissed() throws Exception {
        UUID e = expense("purchase_check");
        vote(e, juror1, "agree", 201);

        mockMvc.perform(post(base(e) + "/trial/judge").with(as(juror1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verdict").value("dismissed"))
                .andExpect(jsonPath("$.sentenceDays").doesNotExist());
    }

    @Test
    @DisplayName("살까 말까 판결: 0표 → dismissed (돈 썼어요처럼 409 가 아니다)")
    void purchaseNoVotesIsDismissed() throws Exception {
        UUID e = expense("purchase_check");
        mockMvc.perform(get(base(e) + "/trial").with(as(juror1))).andExpect(status().isOk());

        mockMvc.perform(post(base(e) + "/trial/judge").with(as(juror1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verdict").value("dismissed"));
    }

    @Test
    @DisplayName("살까 말까 판결 뒤 투표 → 409")
    void voteAfterPurchaseVerdictIs409() throws Exception {
        UUID e = expense("purchase_check");
        vote(e, juror1, "agree", 201);
        mockMvc.perform(post(base(e) + "/trial/judge").with(as(juror1))).andExpect(status().isOk());

        vote(e, juror2, "agree", 409);
    }

    @Test
    @DisplayName("돈 썼어요 판결은 그대로: 유죄 → guilty, 형량 채움")
    void quickTapGuiltyUnchanged() throws Exception {
        UUID e = expense("quick_tap");
        vote(e, juror1, "guilty", 201);

        mockMvc.perform(post(base(e) + "/trial/judge").with(as(juror1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verdict").value("guilty"))
                .andExpect(jsonPath("$.sentenceDays").isNumber())
                .andExpect(jsonPath("$.guiltyVotes").value(1))
                .andExpect(jsonPath("$.notGuiltyVotes").value(0));
    }
}
