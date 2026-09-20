package com.ttegeoji.backend.internal;

import com.ttegeoji.backend.jobs.JobKind;
import com.ttegeoji.backend.support.PostgresContainerSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 19 §5 POST /internal/v1/posts/{post_id}/jury-votes. 실제 인증 체인·예외 처리·평결 확정까지 본다.
 * 표 INSERT 뒤 평결 확정(VerdictConfirmationService)이 새 트랜잭션·job INSERT 를 만들므로 테스트 트랜잭션을 두지 않고
 * 매 테스트 새 UUID 로 격리한다(PostVoteControllerTest 와 같다). 커밋된 RUNNING job 은 lease 가 지나면 reaper 가 회수해
 * ReaperSchedulerTest 의 "회수 0건" 을 깨뜨리므로 테스트가 끝날 때 CANCELLED 로 돌린다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class JuryVoteControllerTest extends PostgresContainerSupport {

    private static final String LEASE = "now() + interval '30 seconds'";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private InternalFixtures fx;
    private UUID juror;
    private UUID author;
    private UUID human;
    private UUID room;
    private UUID post;
    private final List<UUID> jobs = new ArrayList<>();

    @AfterEach
    void cancelJobs() {
        for (UUID job : jobs) {
            jdbc.update("""
                    UPDATE ai.jobs SET status = 'CANCELLED', owner_id = NULL, generation_id = NULL, lease_until = NULL
                     WHERE id = ? AND status IN ('QUEUED', 'RUNNING')""", job);
        }
        jobs.clear();
    }

    @BeforeEach
    void seed() {
        fx = new InternalFixtures(jdbc);
        juror = fx.aiJuror();
        author = fx.profile();
        human = fx.profile();
        room = fx.room(author, "spicy", 1);
        fx.member(room, author);
        fx.member(room, human);
        fx.member(room, juror);
        // 가능 인원 = human + 봇 = 2 라 봇 한 표로는 확정되지 않는다
        post = fx.post(author, "spent");
        fx.share(post, room);
    }

    private static String body(UUID jobId, UUID generation, UUID roomId, UUID voter, String verdict, String reason,
                               String source, String extra) {
        return "{\"job_id\": \"" + jobId + "\", \"generation_id\": \"" + generation + "\", \"room_id\": \"" + roomId
                + "\", \"voter_id\": \"" + voter + "\", \"verdict\": " + (verdict == null ? "null" : "\"" + verdict + "\"")
                + ", \"reason\": " + (reason == null ? "null" : "\"" + reason + "\"") + ", \"source\": \"" + source
                + "\"" + extra + "}";
    }

    private UUID track(UUID jobId) {
        jobs.add(jobId);
        return jobId;
    }

    private UUID runningJob(UUID postId, UUID generation) {
        return track(fx.runningJob(JobKind.JURY_VOTE, InternalFixtures.juryVotePayload(postId, room, juror), generation,
                LEASE));
    }

    private ResultActions cast(Object postId, UUID generation, String json, boolean token) throws Exception {
        MockHttpServletRequestBuilder request = post("/internal/v1/posts/{post_id}/jury-votes", postId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .header("X-Trace-Id", "trace-" + UUID.randomUUID())
                .header("X-Request-Id", UUID.randomUUID().toString())
                .header("X-Job-Id", UUID.randomUUID().toString());
        if (token) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer test-token");
        }
        if (generation != null) {
            request.header("X-Generation-Id", generation.toString());
        }
        return mockMvc.perform(request);
    }

    private ResultActions castOk(UUID jobId, UUID generation, String verdict, String reason) throws Exception {
        return cast(post, generation, body(jobId, generation, room, juror, verdict, reason, "AI", ""), true);
    }

    private int voteCount(UUID postId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM votes WHERE post_id = ?", Integer.class, postId);
        return n == null ? 0 : n;
    }

    private boolean verdictExists(UUID postId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM verdicts WHERE post_id = ?)", Boolean.class, postId));
    }

    private static String code(String code) {
        return "{\"code\":\"" + code + "\"}";
    }

    @Test
    @DisplayName("19 §5 정상 → 201 {vote_id}, votes 행 voter=봇·room·verdict·reason 그대로, 표 1개로는 평결 없음")
    void castsVote() throws Exception {
        UUID generation = UUID.randomUUID();
        UUID job = runningJob(post, generation);

        castOk(job, generation, "guilty", "변명 낭독 끝. 유죄.")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.vote_id").isString())
                .andExpect(jsonPath("$.*", org.hamcrest.Matchers.hasSize(1)));

        var row = jdbc.queryForMap(
                "SELECT voter_id, room_id, verdict::text AS verdict, reason FROM votes WHERE post_id = ?", post);
        assertThat(row).containsEntry("voter_id", juror).containsEntry("room_id", room)
                .containsEntry("verdict", "guilty").containsEntry("reason", "변명 낭독 끝. 유죄.");
        assertThat(verdictExists(post)).isFalse();
    }

    @Test
    @DisplayName("19 §1·§9 사람 1 + 봇 1 방 — 봇 표 도착으로 정족수 min(2, 1)=1 충족, 같은 요청 안에서 평결 확정")
    void confirmsVerdictInSoloRoom() throws Exception {
        UUID soloRoom = fx.room(author, "mild", 1);
        fx.member(soloRoom, author);
        fx.member(soloRoom, juror);
        UUID soloPost = fx.post(author, "considering");
        fx.share(soloPost, soloRoom);
        UUID generation = UUID.randomUUID();
        UUID job = track(fx.runningJob(JobKind.JURY_VOTE, InternalFixtures.juryVotePayload(soloPost, soloRoom, juror),
                generation, LEASE));

        cast(soloPost, generation, body(job, generation, soloRoom, juror, "disagree", "일주일 재워.", "TEMPLATE", ""), true)
                .andExpect(status().isCreated());

        assertThat(verdictExists(soloPost)).isTrue();
        assertThat(jdbc.queryForObject("SELECT jury_result::text FROM verdicts WHERE post_id = ?", String.class, soloPost))
                .isEqualTo("disagree");
    }

    @Test
    @DisplayName("19 §5 2단계 voter_id 가 사람 → 403 NOT_AI_JUROR, 표 없음")
    void humanVoterIsRejected() throws Exception {
        UUID generation = UUID.randomUUID();
        UUID job = runningJob(post, generation);

        cast(post, generation, body(job, generation, room, human, "guilty", "사유", "AI", ""), true)
                .andExpect(status().isForbidden())
                .andExpect(content().json(code("NOT_AI_JUROR"), JsonCompareMode.STRICT));
        assertThat(voteCount(post)).isZero();
    }

    @Test
    @DisplayName("19 §5 재전송(같은 job) → 두 번째는 409 ALREADY_VOTED, 표 1개")
    void retransmitIsAlreadyVoted() throws Exception {
        UUID generation = UUID.randomUUID();
        UUID job = runningJob(post, generation);

        castOk(job, generation, "guilty", "첫 표").andExpect(status().isCreated());
        castOk(job, generation, "guilty", "첫 표")
                .andExpect(status().isConflict())
                .andExpect(content().json(code("ALREADY_VOTED"), JsonCompareMode.STRICT));
        assertThat(voteCount(post)).isEqualTo(1);
    }

    @Test
    @DisplayName("19 §5 4단계 마감 지난 글 → 409 VOTING_CLOSED")
    void deadlinePassedIsClosed() throws Exception {
        jdbc.update("UPDATE posts SET vote_deadline_at = now() - interval '1 minute' WHERE id = ?", post);
        UUID generation = UUID.randomUUID();
        UUID job = runningJob(post, generation);

        castOk(job, generation, "guilty", "사유")
                .andExpect(status().isConflict())
                .andExpect(content().json(code("VOTING_CLOSED"), JsonCompareMode.STRICT));
        assertThat(voteCount(post)).isZero();
    }

    @Test
    @DisplayName("19 §5 4단계 그 방 평결 확정됨·공유 철회·봇이 멤버 아님 → 409 VOTING_CLOSED")
    void closedRoomVariants() throws Exception {
        UUID generation = UUID.randomUUID();
        UUID job = runningJob(post, generation);

        // 봇이 멤버가 아닌 공유 방
        UUID otherRoom = fx.room(author, "hell", 1);
        fx.share(post, otherRoom);
        cast(post, generation, body(job, generation, otherRoom, juror, "guilty", "사유", "AI", ""), true)
                .andExpect(status().isConflict())
                .andExpect(content().json(code("VOTING_CLOSED"), JsonCompareMode.STRICT));

        // 공유 철회
        jdbc.update("UPDATE post_rooms SET revoked_at = now() WHERE post_id = ? AND room_id = ?", post, room);
        castOk(job, generation, "guilty", "사유").andExpect(status().isConflict())
                .andExpect(content().json(code("VOTING_CLOSED"), JsonCompareMode.STRICT));
        jdbc.update("UPDATE post_rooms SET revoked_at = NULL WHERE post_id = ? AND room_id = ?", post, room);

        // 평결 확정
        fx.verdict(post, "guilty");
        castOk(job, generation, "guilty", "사유").andExpect(status().isConflict())
                .andExpect(content().json(code("VOTING_CLOSED"), JsonCompareMode.STRICT));
        assertThat(voteCount(post)).isZero();
    }

    @Test
    @DisplayName("19 §5 3단계 글 없음·삭제됨 → 404 NOT_FOUND")
    void missingOrDeletedPost() throws Exception {
        UUID generation = UUID.randomUUID();
        UUID job = runningJob(post, generation);

        cast(UUID.randomUUID(), generation, body(job, generation, room, juror, "guilty", "사유", "AI", ""), true)
                .andExpect(status().isNotFound())
                .andExpect(content().json(code("NOT_FOUND"), JsonCompareMode.STRICT));

        fx.deletePost(post);
        castOk(job, generation, "guilty", "사유")
                .andExpect(status().isNotFound())
                .andExpect(content().json(code("NOT_FOUND"), JsonCompareMode.STRICT));
    }

    @Test
    @DisplayName("19 §5 1단계 job 없음·QUEUED·generation 불일치·헤더와 본문 불일치·lease 만료 → 409 STALE_GENERATION")
    void staleGenerationVariants() throws Exception {
        UUID generation = UUID.randomUUID();

        cast(post, generation, body(UUID.randomUUID(), generation, room, juror, "guilty", "사유", "AI", ""), true)
                .andExpect(status().isConflict())
                .andExpect(content().json(code("STALE_GENERATION"), JsonCompareMode.STRICT));

        UUID queued = track(fx.job(JobKind.JURY_VOTE, "QUEUED", InternalFixtures.juryVotePayload(post, room, juror)));
        cast(post, generation, body(queued, generation, room, juror, "guilty", "사유", "AI", ""), true)
                .andExpect(status().isConflict())
                .andExpect(content().json(code("STALE_GENERATION"), JsonCompareMode.STRICT));

        UUID running = runningJob(post, generation);
        cast(post, UUID.randomUUID(), body(running, generation, room, juror, "guilty", "사유", "AI", ""), true)
                .andExpect(status().isConflict())
                .andExpect(content().json(code("STALE_GENERATION"), JsonCompareMode.STRICT));
        cast(post, null, body(running, generation, room, juror, "guilty", "사유", "AI", ""), true)
                .andExpect(status().isConflict())
                .andExpect(content().json(code("STALE_GENERATION"), JsonCompareMode.STRICT));

        UUID expired = track(fx.runningJob(JobKind.JURY_VOTE, InternalFixtures.juryVotePayload(post, room, juror),
                generation, "now() - interval '1 second'"));
        cast(post, generation, body(expired, generation, room, juror, "guilty", "사유", "AI", ""), true)
                .andExpect(status().isConflict())
                .andExpect(content().json(code("STALE_GENERATION"), JsonCompareMode.STRICT));
        assertThat(voteCount(post)).isZero();
    }

    @Test
    @DisplayName("19 §5 1단계는 2단계보다 먼저 — job 이 없으면 사람 voter_id 라도 409 STALE_GENERATION")
    void jobCheckPrecedesVoterCheck() throws Exception {
        UUID generation = UUID.randomUUID();
        cast(post, generation, body(UUID.randomUUID(), generation, room, human, "guilty", "사유", "AI", ""), true)
                .andExpect(status().isConflict())
                .andExpect(content().json(code("STALE_GENERATION"), JsonCompareMode.STRICT));
    }

    @Test
    @DisplayName("19 §5 6단계 spent 글에 agree·빈 reason·501자 → 422 INVALID_REQUEST, 표 없음")
    void invalidVerdictOrReason() throws Exception {
        UUID generation = UUID.randomUUID();
        UUID job = runningJob(post, generation);

        castOk(job, generation, "agree", "사유")
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().json(code("INVALID_REQUEST"), JsonCompareMode.STRICT));
        castOk(job, generation, "guilty", "   ")
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().json(code("INVALID_REQUEST"), JsonCompareMode.STRICT));
        castOk(job, generation, "guilty", "가".repeat(501))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().json(code("INVALID_REQUEST"), JsonCompareMode.STRICT));
        assertThat(voteCount(post)).isZero();
    }

    @Test
    @DisplayName("10 §4.7 본문 스키마 위반(알 수 없는 키·source 값·빈 본문) → 422 INVALID_REQUEST — job 검증보다 먼저")
    void schemaViolationIs422() throws Exception {
        UUID generation = UUID.randomUUID();
        UUID job = runningJob(post, generation);

        cast(post, generation, body(job, generation, room, juror, "guilty", "사유", "AI", ", \"extra\": 1"), true)
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().json(code("INVALID_REQUEST"), JsonCompareMode.STRICT));
        cast(post, generation, body(job, generation, room, juror, "guilty", "사유", "RULE", ""), true)
                .andExpect(status().isUnprocessableContent());
        cast(post, generation, "", true).andExpect(status().isUnprocessableContent());
        cast(post, generation, body(UUID.randomUUID(), generation, room, juror, "guilty", "사유", "AI", ", \"x\": 1"), true)
                .andExpect(status().isUnprocessableContent());
        assertThat(voteCount(post)).isZero();
    }

    @Test
    @DisplayName("10 §4.7 서비스 토큰 없음 → 401 {code: UNAUTHORIZED}")
    void missingToken() throws Exception {
        UUID generation = UUID.randomUUID();
        UUID job = runningJob(post, generation);

        cast(post, generation, body(job, generation, room, juror, "guilty", "사유", "AI", ""), false)
                .andExpect(status().isUnauthorized())
                .andExpect(content().json(code("UNAUTHORIZED"), JsonCompareMode.STRICT));
        assertThat(voteCount(post)).isZero();
    }
}
