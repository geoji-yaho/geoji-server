package com.ttegeoji.backend.seed;

import com.ttegeoji.backend.ai.IntakeClient.IntakeResult;
import com.ttegeoji.backend.ai.IntakeClient.Mode;
import com.ttegeoji.backend.domain.enums.IntakeStatus;
import com.ttegeoji.backend.domain.enums.PostType;
import com.ttegeoji.backend.jobs.JobKind;
import com.ttegeoji.backend.submission.PostCreator;
import com.ttegeoji.backend.submission.SubmissionPayload;
import com.ttegeoji.backend.support.PostgresContainerSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 19 §2 GEOJI_AI_JUROR_USER_ID 미설정. ai-member 503, JURY_VOTE INSERT 0, jury-votes 403(19 §9 마지막 두 줄).
 * application-test.yml 의 봇 id 를 비우므로 컨텍스트가 하나 더 뜬다 — 미설정 케이스는 전부 이 클래스에 모은다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "geoji.ai-juror-user-id=")
class AiJurorUnconfiguredTest extends PostgresContainerSupport {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PostCreator postCreator;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private UUID profile() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO profiles (id, nickname, monthly_budget) VALUES (?, 'n', 0)", id);
        return id;
    }

    private UUID room(UUID owner) {
        UUID id = jdbc.queryForObject("""
                INSERT INTO rooms (name, spice_level, vote_deadline_minutes, created_by)
                VALUES ('room', 'mild', 30, ?) RETURNING id""", UUID.class, owner);
        jdbc.update("INSERT INTO room_members (room_id, user_id) VALUES (?, ?)", id, owner);
        return id;
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    @Test
    @DisplayName("19 §6 미설정 → ai-member 503 {code: AI_JUROR_NOT_CONFIGURED}, 멤버·글 없음")
    void aiMemberIs503() throws Exception {
        UUID owner = profile();
        UUID room = room(owner);

        mockMvc.perform(post("/api/rooms/" + room + "/ai-member").with(jwt().jwt(t -> t.subject(owner.toString()))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AI_JUROR_NOT_CONFIGURED"));

        assertThat(count("SELECT count(*) FROM room_members WHERE room_id = ?", room)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM post_rooms WHERE room_id = ?", room)).isZero();
    }

    @Test
    @DisplayName("19 §3 미설정 → 게시물 저장에 JURY_VOTE INSERT 0, PREPARE 는 그대로 1")
    void noJuryVoteJob() {
        UUID author = profile();
        UUID room = room(author);
        UUID submission = jdbc.queryForObject("""
                INSERT INTO submissions (actor_id, payload_hash, expires_at) VALUES (?, 'h', now() + interval '1 hour')
                RETURNING id""", UUID.class, author);

        UUID postId = postCreator.create(submission,
                SubmissionPayload.normalize(PostType.spent, 12000, "배달", "치킨", "야식", List.of(room)),
                IntakeResult.fallback(Mode.INITIAL), IntakeStatus.PASS);

        assertThat(count("SELECT count(*) FROM ai.jobs WHERE kind = 'JURY_VOTE' AND aggregate_id = ?", postId.toString()))
                .isZero();
        assertThat(count("SELECT count(*) FROM ai.jobs WHERE kind = 'PREPARE' AND aggregate_id = ?", postId.toString()))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("19 §5 2단계 미설정 → jury-votes 는 어떤 voter_id 라도 403 NOT_AI_JUROR")
    void juryVotesIs403() throws Exception {
        UUID author = profile();
        UUID voter = profile();
        UUID room = room(author);
        UUID post = jdbc.queryForObject("""
                INSERT INTO posts (author_id, post_type, amount_krw, category, item, reason, intake_status,
                                   intake_source, vote_deadline_at)
                VALUES (?, 'spent', 9000, '식비', '김밥', '배고파서', 'PASS', 'AI', now() + interval '1 hour')
                RETURNING id""", UUID.class, author);
        UUID generation = UUID.randomUUID();
        UUID job = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO ai.jobs (id, event_id, event_type, kind, dedupe_key, aggregate_id, aggregate_version,
                                     payload, status, priority, attempts, max_attempts, lease_until, owner_id,
                                     generation_id, trace_id)
                VALUES (?, gen_random_uuid(), 'jury.vote_requested', ?, ?, ?, 1, CAST(? AS jsonb), 'RUNNING', 60, 1, 2,
                        now() + interval '30 seconds', 'worker', ?, 'trace')
                """, job, JobKind.JURY_VOTE.name(), "test:" + job, post.toString(),
                "{\"post_id\": \"" + post + "\", \"post_version\": 1, \"room_id\": \"" + room + "\", \"voter_id\": \""
                        + voter + "\"}", generation);

        mockMvc.perform(post("/internal/v1/posts/" + post + "/jury-votes")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                        .header("X-Generation-Id", generation.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"job_id\": \"" + job + "\", \"generation_id\": \"" + generation + "\", \"room_id\": \""
                                + room + "\", \"voter_id\": \"" + voter
                                + "\", \"verdict\": \"guilty\", \"reason\": \"사유\", \"source\": \"AI\"}"))
                .andExpect(status().isForbidden())
                .andExpect(content().json("{\"code\":\"NOT_AI_JUROR\"}", JsonCompareMode.STRICT));
        assertThat(count("SELECT count(*) FROM votes WHERE post_id = ?", post)).isZero();
        // 커밋된 RUNNING job 이 lease 뒤 reaper 에 잡히지 않게 끝낸다(ReaperSchedulerTest "회수 0건")
        jdbc.update("UPDATE ai.jobs SET status = 'CANCELLED', owner_id = NULL, generation_id = NULL, lease_until = NULL WHERE id = ?",
                job);
    }
}
