package com.ttegeoji.backend.internal;

import com.ttegeoji.backend.jobs.JobKind;
import com.ttegeoji.backend.support.PostgresContainerSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 실제 인증 체인·예외 처리까지 본다. MockMvc 는 같은 스레드라 @Transactional 테스트 행이 컨트롤러에서도 보인다(롤백).
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AiJobControllerTest extends PostgresContainerSupport {

    private static final String STALE = "{\"code\":\"STALE_GENERATION\"}";
    private static final String INVALID = "{\"code\":\"INVALID_REQUEST\"}";
    private static final String LEASE = "now() + interval '30 seconds'";
    private static final String SECRET = "secret-input-5c1e";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ObjectMapper objectMapper;

    // 공개 체인의 issuer-uri 디코더가 밖으로 나가지 않게 막는다
    @MockitoBean
    private JwtDecoder jwtDecoder;

    private InternalFixtures fx;

    @BeforeEach
    void setUp() {
        fx = new InternalFixtures(jdbcTemplate);
    }

    /** 10 §4.7 워커 헤더 5종. generationId 가 null 이면 X-Generation-Id 를 빼고 보낸다. */
    private static MockHttpServletRequestBuilder withWorkerHeaders(MockHttpServletRequestBuilder builder, Object jobId,
                                                                   String generationId) {
        builder.header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .header("X-Trace-Id", "trace-" + UUID.randomUUID())
                .header("X-Request-Id", UUID.randomUUID().toString())
                .header("X-Job-Id", String.valueOf(jobId));
        if (generationId != null) {
            builder.header("X-Generation-Id", generationId);
        }
        return builder;
    }

    private MockHttpServletRequestBuilder snapshotRequest(Object jobId, String generationId) {
        return withWorkerHeaders(get("/internal/v1/ai-jobs/{job_id}/snapshot", jobId), jobId, generationId);
    }

    private MockHttpServletRequestBuilder resolveRequest(Object jobId, String generationId, String body) {
        return withWorkerHeaders(post("/internal/v1/ai-jobs/{job_id}/resolve-evidence", jobId), jobId, generationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private static String candidate(String extra) {
        return "{\"source_type\": \"POST\", \"source_id\": \"" + SECRET + "\", \"source_version\": 1, \"score\": 0.5"
                + extra + "}";
    }

    private UUID sentenceCase(UUID generation, String leaseExpr) {
        UUID author = fx.profile();
        UUID room = fx.room(author, "spicy", 1);
        UUID post = fx.post(author, "spent");
        fx.share(post, room);
        fx.vote(post, room, "guilty");
        fx.vote(post, room, "notGuilty");
        UUID verdict = fx.verdict(post, "guilty");
        return fx.runningJob(JobKind.SENTENCE, InternalFixtures.sentencePayload(verdict, post), generation, leaseExpr);
    }

    @Test
    @DisplayName("10 §4.7 토큰 없음 → 401 {\"code\":\"UNAUTHORIZED\"}")
    void missingToken() throws Exception {
        UUID generation = UUID.randomUUID();
        UUID jobId = sentenceCase(generation, LEASE);

        mockMvc.perform(get("/internal/v1/ai-jobs/{job_id}/snapshot", jobId)
                        .header("X-Generation-Id", generation.toString()))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(content().json("{\"code\":\"UNAUTHORIZED\"}", JsonCompareMode.STRICT));
    }

    @Test
    @DisplayName("10 §4.1 job 없음 → 409 STALE_GENERATION")
    void jobMissing() throws Exception {
        mockMvc.perform(snapshotRequest(UUID.randomUUID(), UUID.randomUUID().toString()))
                .andExpect(status().isConflict())
                .andExpect(content().json(STALE, JsonCompareMode.STRICT));
    }

    @Test
    @DisplayName("10 §4.1 job QUEUED(RUNNING 아님) → 409 STALE_GENERATION")
    void jobNotRunning() throws Exception {
        UUID jobId = fx.job(JobKind.PREPARE, "QUEUED", InternalFixtures.preparePayload(UUID.randomUUID()));

        mockMvc.perform(snapshotRequest(jobId, UUID.randomUUID().toString()))
                .andExpect(status().isConflict())
                .andExpect(content().json(STALE, JsonCompareMode.STRICT));
    }

    @Test
    @DisplayName("10 §4.1 RUNNING 이지만 X-Generation-Id 불일치 → 409 STALE_GENERATION")
    void generationMismatch() throws Exception {
        UUID jobId = sentenceCase(UUID.randomUUID(), LEASE);

        mockMvc.perform(snapshotRequest(jobId, UUID.randomUUID().toString()))
                .andExpect(status().isConflict())
                .andExpect(content().json(STALE, JsonCompareMode.STRICT));
    }

    @Test
    @DisplayName("10 §4.1 RUNNING·generation 일치·lease 만료 → 409 STALE_GENERATION")
    void leaseExpired() throws Exception {
        UUID generation = UUID.randomUUID();
        UUID jobId = sentenceCase(generation, "now() - interval '1 second'");

        mockMvc.perform(snapshotRequest(jobId, generation.toString()))
                .andExpect(status().isConflict())
                .andExpect(content().json(STALE, JsonCompareMode.STRICT));
    }

    @Test
    @DisplayName("10 §4.1 X-Generation-Id 헤더 없음 → 409 STALE_GENERATION(500 아님)")
    void generationHeaderMissing() throws Exception {
        UUID jobId = sentenceCase(UUID.randomUUID(), LEASE);

        mockMvc.perform(snapshotRequest(jobId, null))
                .andExpect(status().isConflict())
                .andExpect(content().json(STALE, JsonCompareMode.STRICT));
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-uuid", "", "1-1-1-1-1"})
    @DisplayName("10 §4.1 X-Generation-Id 가 UUID 아님 → 409 STALE_GENERATION(500 아님)")
    void generationHeaderNotUuid(String generation) throws Exception {
        UUID jobId = sentenceCase(UUID.randomUUID(), LEASE);

        mockMvc.perform(snapshotRequest(jobId, generation))
                .andExpect(status().isConflict())
                .andExpect(content().json(STALE, JsonCompareMode.STRICT));
    }

    @Test
    @DisplayName("10 §4.1 path job_id 가 UUID 아님 → 409 STALE_GENERATION")
    void jobIdNotUuid() throws Exception {
        mockMvc.perform(snapshotRequest("not-a-uuid", UUID.randomUUID().toString()))
                .andExpect(status().isConflict())
                .andExpect(content().json(STALE, JsonCompareMode.STRICT));
    }

    @Test
    @DisplayName("10 §4.1·§4.7 헤더 5종 정상 snapshot → 200 CaseSnapshot")
    void snapshotOk() throws Exception {
        UUID generation = UUID.randomUUID();
        UUID jobId = sentenceCase(generation, LEASE);

        String body = mockMvc.perform(snapshotRequest(jobId, generation.toString()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        JsonNode n = objectMapper.readTree(body);
        CaseSnapshotShape.assertValid(n);
        org.assertj.core.api.Assertions.assertThat(n.get("item").stringValue()).isEqualTo("택시");
        org.assertj.core.api.Assertions.assertThat(n.get("jury").get("guilty_ratio").doubleValue()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("10 §4.2·§4.7 헤더 5종 정상 resolve-evidence → 200, 다섯 키")
    void resolveOk() throws Exception {
        UUID generation = UUID.randomUUID();
        UUID jobId = sentenceCase(generation, LEASE);

        String body = mockMvc.perform(resolveRequest(jobId, generation.toString(),
                        "{\"candidates\": [], \"include\": [\"rules\", \"aggregates\", \"recent_verdicts\"]}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        JsonNode n = objectMapper.readTree(body);
        EvidenceShape.assertValid(n);
        org.assertj.core.api.Assertions.assertThat(n.get("style_comments").isEmpty()).isTrue();
    }

    @Test
    @DisplayName("10 §4.1 §0.1 9/14 RETAIN 원본 게시물 삭제 → 404 {\"code\":\"NOT_FOUND\"}")
    void retainDeletedPostIs404() throws Exception {
        UUID generation = UUID.randomUUID();
        UUID author = fx.profile();
        UUID room = fx.room(author, "spicy", 1);
        UUID post = fx.post(author, "spent");
        fx.share(post, room);
        UUID verdict = fx.verdict(post, "guilty");
        fx.finalizeVerdict(verdict, "probation", "RULE", null, "TEMPLATE", "mild");
        fx.deletePost(post);
        UUID jobId = fx.runningJob(JobKind.RETAIN, InternalFixtures.retainVerdictPayload(verdict), generation, LEASE);

        mockMvc.perform(snapshotRequest(jobId, generation.toString()))
                .andExpect(status().isNotFound())
                .andExpect(content().json("{\"code\":\"NOT_FOUND\"}", JsonCompareMode.STRICT));
    }

    @Test
    @DisplayName("10 §4.1 job 검증이 삭제 판정보다 먼저 — 삭제된 원본이어도 generation 불일치면 409")
    void staleJobWinsOverDeleted() throws Exception {
        UUID author = fx.profile();
        UUID room = fx.room(author, "spicy", 1);
        UUID post = fx.post(author, "spent");
        fx.share(post, room);
        UUID verdict = fx.verdict(post, "guilty");
        fx.deletePost(post);
        UUID jobId = fx.runningJob(JobKind.RETAIN, InternalFixtures.retainVerdictPayload(verdict),
                UUID.randomUUID(), LEASE);

        mockMvc.perform(snapshotRequest(jobId, UUID.randomUUID().toString()))
                .andExpect(status().isConflict())
                .andExpect(content().json(STALE, JsonCompareMode.STRICT));
    }

    @Test
    @DisplayName("10 §4.2 resolve 후보 20개(상한)는 본문 검증을 통과 — 그다음 job 검증 409")
    void resolveTwentyCandidatesPassesValidation() throws Exception {
        String candidates = IntStream.range(0, 20).mapToObj(i -> candidate("")).collect(Collectors.joining(","));

        mockMvc.perform(resolveRequest(UUID.randomUUID(), UUID.randomUUID().toString(),
                        "{\"candidates\": [" + candidates + "], \"include\": [\"rules\"]}"))
                .andExpect(status().isConflict())
                .andExpect(content().json(STALE, JsonCompareMode.STRICT));
    }

    @Test
    @DisplayName("10 §4.2·§4.7 resolve 후보 21개 → 422 INVALID_REQUEST")
    void resolveTooManyCandidates() throws Exception {
        String candidates = IntStream.range(0, 21).mapToObj(i -> candidate("")).collect(Collectors.joining(","));

        mockMvc.perform(resolveRequest(UUID.randomUUID(), UUID.randomUUID().toString(),
                        "{\"candidates\": [" + candidates + "], \"include\": [\"rules\"]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().json(INVALID, JsonCompareMode.STRICT))
                .andExpect(content().string(not(containsString(SECRET))));
    }

    @Test
    @DisplayName("10 §4.2·§4.7 resolve 최상위 알 수 없는 필드 → 422 INVALID_REQUEST")
    void resolveUnknownTopLevelField() throws Exception {
        mockMvc.perform(resolveRequest(UUID.randomUUID(), UUID.randomUUID().toString(),
                        "{\"candidates\": [], \"include\": [\"rules\"], \"extra\": \"" + SECRET + "\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().json(INVALID, JsonCompareMode.STRICT))
                .andExpect(content().string(not(containsString(SECRET))));
    }

    @Test
    @DisplayName("10 §4.2·§4.7 resolve candidate 안 알 수 없는 필드 → 422 INVALID_REQUEST")
    void resolveUnknownCandidateField() throws Exception {
        mockMvc.perform(resolveRequest(UUID.randomUUID(), UUID.randomUUID().toString(),
                        "{\"candidates\": [" + candidate(", \"payload\": {}") + "], \"include\": [\"rules\"]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().json(INVALID, JsonCompareMode.STRICT))
                .andExpect(content().string(not(containsString(SECRET))));
    }

    @Test
    @DisplayName("10 §4.2 resolve include 에 enum 밖 값 → 422 INVALID_REQUEST")
    void resolveIncludeOutOfEnum() throws Exception {
        mockMvc.perform(resolveRequest(UUID.randomUUID(), UUID.randomUUID().toString(),
                        "{\"candidates\": [], \"include\": [\"rules\", \"" + SECRET + "\"]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().json(INVALID, JsonCompareMode.STRICT))
                .andExpect(content().string(not(containsString(SECRET))));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"candidates\": []}",
            "{\"include\": [\"rules\"]}",
            "{\"candidates\": null, \"include\": [\"rules\"]}",
            "{\"candidates\": [{\"source_type\": \"POST\", \"source_id\": \"p\", \"source_version\": 1}], \"include\": []}",
            "{\"candidates\": [null], \"include\": []}",
            "{\"candidates\": [], \"include\": [\"rules\"]",
            "[]",
            ""
    })
    @DisplayName("10 §4.2·§4.7 resolve 필수 누락·null·잘못된 JSON → 422 INVALID_REQUEST")
    void resolveMalformedOrMissing(String body) throws Exception {
        mockMvc.perform(resolveRequest(UUID.randomUUID(), UUID.randomUUID().toString(), body))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().json(INVALID, JsonCompareMode.STRICT));
    }

    @Test
    @DisplayName("10 §4.2 resolve 본문이 맞으면 그다음 job 검증 — job 없음 → 409 STALE_GENERATION")
    void resolveValidBodyThenJobCheck() throws Exception {
        mockMvc.perform(resolveRequest(UUID.randomUUID(), UUID.randomUUID().toString(),
                        "{\"candidates\": [" + candidate("") + "], \"include\": [\"rules\", \"aggregates\"]}"))
                .andExpect(status().isConflict())
                .andExpect(content().json(STALE, JsonCompareMode.STRICT))
                .andExpect(content().string(not(containsString(SECRET))));
    }
}
