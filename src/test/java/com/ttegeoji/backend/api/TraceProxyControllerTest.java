package com.ttegeoji.backend.api;

import com.ttegeoji.backend.ai.AiTraceClient;
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
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 게시물·방은 컨테이너 DB 에 넣고, AI API 는 AiTraceClient 목으로 바꾼다(실제 AI API 를 부르지 않는다)
@SpringBootTest
@AutoConfigureMockMvc
class TraceProxyControllerTest extends PostgresContainerSupport {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private AiTraceClient aiTraceClient;
    // 공개 체인의 issuer-uri 디코더가 Supabase 로 나가지 않게 막는다. 요청은 jwt() 로 인증을 넣는다
    @MockitoBean
    private JwtDecoder jwtDecoder;

    private UUID authorId;
    private UUID memberId;
    private UUID postId;

    @BeforeEach
    void seed() {
        authorId = insertProfile();
        memberId = insertProfile();
        UUID roomId = jdbc.queryForObject("""
                INSERT INTO rooms (name, spice_level, vote_deadline_minutes, created_by)
                VALUES ('trace 방', 'mild'::spice_level, 60, ?) RETURNING id
                """, UUID.class, authorId);
        jdbc.update("INSERT INTO room_members (room_id, user_id) VALUES (?, ?), (?, ?)",
                roomId, authorId, roomId, memberId);
        postId = jdbc.queryForObject("""
                INSERT INTO posts (author_id, post_type, amount_krw, category, item, intake_status, intake_source,
                                   vote_deadline_at)
                VALUES (?, 'spent'::post_type, 12000, '식비', '마라탕', 'PASS', 'AI', now() + interval '1 hour')
                RETURNING id
                """, UUID.class, authorId);
        jdbc.update("INSERT INTO post_rooms (post_id, room_id) VALUES (?, ?)", postId, roomId);
    }

    private UUID insertProfile() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO profiles (id, nickname, monthly_budget) VALUES (?, 'trace', 300000)", id);
        return id;
    }

    private static RequestPostProcessor as(UUID userId) {
        return jwt().jwt(token -> token.subject(userId.toString()));
    }

    private String traceUrl(UUID id) {
        return "/api/posts/" + id + "/trace";
    }

    @Test
    @DisplayName("10 §4.5 작성자 → 200, AI trace 를 camelCase 로 바꿔 돌려준다")
    void authorGetsTrace() throws Exception {
        // AI 저장소 src/geoji_ai/adapters/postgres_telemetry.py trace() 모양
        JsonNode aiTrace = JSON.readTree("""
                {"post_id": "%s",
                 "dossier": {"labels": ["F0", "F1"], "created_at": "2026-09-15T01:00:00+00:00", "invalidated": false,
                             "evidence": [{"label": "F0", "epistemic_type": "OBSERVED", "fact_type": "POST_AMOUNT",
                                           "scope": {"visibility": "ROOM", "room_count": 1},
                                           "occurred_at": null, "invalidated": false,
                                           "sources": [{"source_type": "POST", "count": 1}]}]},
                 "timeline": [{"node": "judge", "call_index": 0, "vendor": "openai", "model_id": "m",
                               "status": "SUCCEEDED", "started_at": null, "finished_at": null, "duration_ms": 1200,
                               "prompt_tokens": 10, "completion_tokens": 20, "actual_micro_usd": 350}],
                 "cost": {"actual_micro_usd": 350, "unknown_calls": 0, "unknown_estimated_max_micro_usd": 0,
                          "calls": 1}}
                """.formatted(postId));
        given(aiTraceClient.fetch(postId)).willReturn(Optional.of(aiTrace));

        mockMvc.perform(get(traceUrl(postId)).with(as(authorId)))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"postId": "%s",
                         "dossier": {"labels": ["F0", "F1"], "createdAt": "2026-09-15T01:00:00+00:00", "invalidated": false,
                                     "evidence": [{"label": "F0", "epistemicType": "OBSERVED", "factType": "POST_AMOUNT",
                                                   "scope": {"visibility": "ROOM", "roomCount": 1},
                                                   "occurredAt": null, "invalidated": false,
                                                   "sources": [{"sourceType": "POST", "count": 1}]}]},
                         "timeline": [{"node": "judge", "callIndex": 0, "vendor": "openai", "modelId": "m",
                                       "status": "SUCCEEDED", "startedAt": null, "finishedAt": null, "durationMs": 1200,
                                       "promptTokens": 10, "completionTokens": 20, "actualMicroUsd": 350}],
                         "cost": {"actualMicroUsd": 350, "unknownCalls": 0, "unknownEstimatedMaxMicroUsd": 0,
                                  "calls": 1}}
                        """.formatted(postId), JsonCompareMode.STRICT));
    }

    @Test
    @DisplayName("10 §1 같은 방 멤버여도 작성자가 아니면 404, AI API 를 부르지 않는다")
    void roomMemberNotAuthorIs404() throws Exception {
        mockMvc.perform(get(traceUrl(postId)).with(as(memberId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").exists());

        verify(aiTraceClient, never()).fetch(any());
    }

    @Test
    @DisplayName("10 §1 삭제된 게시물 → 작성자여도 404, AI API 를 부르지 않는다")
    void deletedPostIs404() throws Exception {
        jdbc.update("UPDATE posts SET deleted_at = now() WHERE id = ?", postId);

        mockMvc.perform(get(traceUrl(postId)).with(as(authorId)))
                .andExpect(status().isNotFound());

        verify(aiTraceClient, never()).fetch(any());
    }

    @Test
    @DisplayName("10 §1 없는 게시물 → 404, 작성자 아님과 같은 본문")
    void unknownPostIs404() throws Exception {
        String notMine = mockMvc.perform(get(traceUrl(postId)).with(as(memberId)))
                .andReturn().getResponse().getContentAsString();

        mockMvc.perform(get(traceUrl(UUID.randomUUID())).with(as(authorId)))
                .andExpect(status().isNotFound())
                .andExpect(content().json(notMine, JsonCompareMode.STRICT));

        verify(aiTraceClient, never()).fetch(any());
    }

    @Test
    @DisplayName("10 §4.5 trace 기록 없음(TRACE_NOT_FOUND) → 404")
    void traceMissingIs404() throws Exception {
        given(aiTraceClient.fetch(postId)).willReturn(Optional.empty());

        mockMvc.perform(get(traceUrl(postId)).with(as(authorId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    @DisplayName("JWT 없음 → 401, AI API 를 부르지 않는다")
    void missingJwtIs401() throws Exception {
        mockMvc.perform(get(traceUrl(postId)))
                .andExpect(status().isUnauthorized());

        verify(aiTraceClient, never()).fetch(any());
    }

    @Test
    @DisplayName("9/15 결정 AI API 5xx·연결 실패·예상 밖 4xx → 502 {message}")
    void aiUnavailableIs502() throws Exception {
        given(aiTraceClient.fetch(postId)).willThrow(new AiTraceClient.TraceUnavailableException("AI API trace 거부 HTTP 503"));

        mockMvc.perform(get(traceUrl(postId)).with(as(authorId)))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    @DisplayName("9/15 결정 AI API read 타임아웃 → 504 {message}")
    void aiTimeoutIs504() throws Exception {
        given(aiTraceClient.fetch(postId)).willThrow(new AiTraceClient.TraceTimeoutException(new java.net.http.HttpTimeoutException("timed out")));

        mockMvc.perform(get(traceUrl(postId)).with(as(authorId)))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.message").exists());
    }
}
