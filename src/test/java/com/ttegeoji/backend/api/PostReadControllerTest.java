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

/** 게시물 읽기. 투표 화면과 판결 화면이 사건 개요·배심원 집계를 그리는 데 쓴다 */
@SpringBootTest
@AutoConfigureMockMvc
class PostReadControllerTest extends PostgresContainerSupport {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private Fixtures f;
    private UUID author;
    private UUID juror;
    private UUID stranger;
    private UUID room;
    private UUID post;

    @BeforeEach
    void seed() {
        f = new Fixtures(jdbc);
        author = f.profile();
        juror = f.profile();
        stranger = f.profile();
        room = f.room(author, "spicy");
        f.member(room, author);
        f.member(room, juror);
        post = f.post(author, "spent", "now() + interval '30 minutes'");
        f.share(post, room);
        jdbc.update("UPDATE profiles SET nickname = '피고' WHERE id = ?", author);
        jdbc.update("UPDATE profiles SET nickname = '배심원' WHERE id = ?", juror);
    }

    private void vote(UUID voter, String verdict, String reason) {
        jdbc.update("""
                INSERT INTO votes (post_id, voter_id, room_id, verdict, reason)
                VALUES (?, ?, ?, CAST(? AS verdict), ?)""", post, voter, room, verdict, reason);
    }

    @Test
    @DisplayName("작성자가 자기 게시물을 본다 → 사건 개요·공유 방·투표 가능 인원")
    void authorSeesDetail() throws Exception {
        mockMvc.perform(get("/api/posts/" + post).with(as(author)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(post.toString()))
                .andExpect(jsonPath("$.postType").value("spent"))
                .andExpect(jsonPath("$.amountKrw").value(98765))
                .andExpect(jsonPath("$.category").value("식비"))
                .andExpect(jsonPath("$.item").value("비밀마라탕"))
                .andExpect(jsonPath("$.reason").value("야근했음"))
                .andExpect(jsonPath("$.authorNickname").value("피고"))
                .andExpect(jsonPath("$.rooms[0].id").value(room.toString()))
                .andExpect(jsonPath("$.rooms[0].spiceLevel").value("spicy"))
                .andExpect(jsonPath("$.juryStatus").doesNotExist())
                .andExpect(jsonPath("$.eligibleVoterCount").value(1))
                .andExpect(jsonPath("$.canVote").value(false));
    }

    @Test
    @DisplayName("공유 방 멤버는 투표할 수 있다 → canVote=true, 아직 표가 없으면 집계 0")
    void memberCanVote() throws Exception {
        mockMvc.perform(get("/api/posts/" + post).with(as(juror)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canVote").value(true))
                .andExpect(jsonPath("$.myVote").doesNotExist())
                .andExpect(jsonPath("$.tally.oppose").value(0))
                .andExpect(jsonPath("$.tally.support").value(0));
    }

    @Test
    @DisplayName("평결 확정 전에는 남의 투표 사유를 감춘다. 집계는 보인다")
    void hidesReasonsBeforeVerdict() throws Exception {
        vote(juror, "guilty", "지하철이 있었잖아요");

        mockMvc.perform(get("/api/posts/" + post).with(as(author)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tally.oppose").value(1))
                .andExpect(jsonPath("$.votes[0].voterNickname").value("배심원"))
                .andExpect(jsonPath("$.votes[0].verdict").value("guilty"))
                .andExpect(jsonPath("$.votes[0].reason").doesNotExist());
    }

    @Test
    @DisplayName("평결이 확정되면 투표 사유가 보인다")
    void showsReasonsAfterVerdict() throws Exception {
        vote(juror, "guilty", "지하철이 있었잖아요");
        f.verdict(post, "guilty", "FINAL", "oneDay", "이유", "AI_READY", 1, "spicy");

        mockMvc.perform(get("/api/posts/" + post).with(as(author)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.juryStatus").value("guilty"))
                .andExpect(jsonPath("$.votes[0].reason").value("지하철이 있었잖아요"))
                .andExpect(jsonPath("$.canVote").value(false));
    }

    @Test
    @DisplayName("이미 투표했으면 myVote 가 채워지고 다시 투표할 수 없다")
    void myVoteIsReturned() throws Exception {
        vote(juror, "notGuilty", "그럴 수 있죠");

        mockMvc.perform(get("/api/posts/" + post).with(as(juror)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.myVote.verdict").value("notGuilty"))
                .andExpect(jsonPath("$.canVote").value(false))
                .andExpect(jsonPath("$.tally.support").value(1));
    }

    @Test
    @DisplayName("공유 방 멤버가 아니면 404. 없는 글과 구분하지 않는다")
    void strangerGets404() throws Exception {
        mockMvc.perform(get("/api/posts/" + post).with(as(stranger)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("게시물을 찾을 수 없습니다."));
    }

    @Test
    @DisplayName("삭제된 게시물은 작성자에게도 404")
    void deletedPostIs404() throws Exception {
        jdbc.update("UPDATE posts SET deleted_at = now() WHERE id = ?", post);

        mockMvc.perform(get("/api/posts/" + post).with(as(author)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("공유가 철회되면 그 방 멤버는 더 볼 수 없다")
    void revokedShareHidesPost() throws Exception {
        f.revoke(post, room);

        mockMvc.perform(get("/api/posts/" + post).with(as(juror)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("방 피드 → 최신 글이 위, 집계와 내 투표 여부를 함께 준다")
    void roomFeedListsPosts() throws Exception {
        vote(juror, "guilty", "지하철이 있었잖아요");
        UUID older = f.post(author, "considering", "now() + interval '30 minutes'");
        f.share(older, room);
        jdbc.update("UPDATE posts SET created_at = now() - interval '1 hour' WHERE id = ?", older);

        mockMvc.perform(get("/api/rooms/" + room + "/posts").with(as(juror)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(post.toString()))
                .andExpect(jsonPath("$[0].tally.oppose").value(1))
                .andExpect(jsonPath("$[0].voted").value(true))
                .andExpect(jsonPath("$[1].id").value(older.toString()))
                .andExpect(jsonPath("$[1].voted").value(false));
    }

    @Test
    @DisplayName("방 피드는 철회된 공유와 삭제된 글을 빼고 준다")
    void roomFeedSkipsRevokedAndDeleted() throws Exception {
        UUID revoked = f.post(author, "spent", "now() + interval '30 minutes'");
        f.share(revoked, room);
        f.revoke(revoked, room);
        UUID deleted = f.post(author, "spent", "now() + interval '30 minutes'");
        f.share(deleted, room);
        jdbc.update("UPDATE posts SET deleted_at = now() WHERE id = ?", deleted);

        mockMvc.perform(get("/api/rooms/" + room + "/posts").with(as(juror)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(post.toString()));
    }

    @Test
    @DisplayName("방 멤버가 아니면 피드가 404")
    void feedForNonMemberIs404() throws Exception {
        mockMvc.perform(get("/api/rooms/" + room + "/posts").with(as(stranger)))
                .andExpect(status().isNotFound());
    }
}
