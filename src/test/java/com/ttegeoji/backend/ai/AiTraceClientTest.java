package com.ttegeoji.backend.ai;

import com.sun.net.httpserver.HttpServer;
import com.ttegeoji.backend.config.AiApiClientConfig;
import com.ttegeoji.backend.config.GeojiProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.http.HttpTimeoutException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

// 실제 AI API 를 부르지 않는다. W1 aiApiRestClient 빈을 그대로 만들어 MockRestServiceServer 를 붙인다
class AiTraceClientTest {

    private static final String BASE_URL = "http://ai.test";
    private static final UUID POST_ID = UUID.fromString("0f6f1d2e-5b1a-4c55-9a3e-2d7c1f0e9b11");
    private static final String TRACE_URL = BASE_URL + "/internal/v1/trials/" + POST_ID + "/trace";

    // AI 저장소 src/geoji_ai/adapters/postgres_telemetry.py trace() 모양을 줄였다
    private static final String TRACE_JSON = """
            {"post_id": "%s", "dossier": null,
             "timeline": [{"node": "judge", "call_index": 0, "status": "SUCCEEDED", "duration_ms": 1200}],
             "cost": {"actual_micro_usd": 350, "unknown_calls": 0, "unknown_estimated_max_micro_usd": 0, "calls": 1}}
            """.formatted(POST_ID);

    private MockRestServiceServer server;
    private AiTraceClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = aiApiRestClient(BASE_URL).mutate();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new AiTraceClient(builder);
    }

    private static RestClient aiApiRestClient(String baseUrl) {
        GeojiProperties properties = new GeojiProperties(
                new GeojiProperties.Internal("test-token"), new GeojiProperties.Ai(baseUrl), "guardrail-v2", null);
        return new AiApiClientConfig().aiApiRestClient(properties);
    }

    @Test
    @DisplayName("10 §16.2 200 → trace JSON 그대로, Authorization: Bearer 서비스 토큰")
    void okPassesThrough() {
        server.expect(requestTo(TRACE_URL))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer test-token"))
                .andRespond(withSuccess(TRACE_JSON, MediaType.APPLICATION_JSON));

        Optional<JsonNode> trace = client.fetch(POST_ID);

        assertThat(trace).isPresent();
        assertThat(trace.get().get("post_id").asString()).isEqualTo(POST_ID.toString());
        assertThat(trace.get().get("cost").get("actual_micro_usd").asInt()).isEqualTo(350);
        assertThat(trace.get().get("timeline").get(0).get("call_index").asInt()).isZero();
        server.verify();
    }

    @Test
    @DisplayName("10 §4.5 404 TRACE_NOT_FOUND → empty")
    void traceNotFoundIsEmpty() {
        server.expect(requestTo(TRACE_URL))
                .andRespond(withStatus(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\": \"TRACE_NOT_FOUND\"}"));

        assertThat(client.fetch(POST_ID)).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("10 §4.7 503 DB_UNAVAILABLE → TraceUnavailableException")
    void dbUnavailableThrows() {
        server.expect(requestTo(TRACE_URL))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\": \"DB_UNAVAILABLE\"}"));

        assertThatThrownBy(() -> client.fetch(POST_ID))
                .isInstanceOf(AiTraceClient.TraceUnavailableException.class);
    }

    @Test
    @DisplayName("10 §4.7 401 UNAUTHORIZED → TraceUnavailableException")
    void unauthorizedThrows() {
        server.expect(requestTo(TRACE_URL))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\": \"UNAUTHORIZED\"}"));

        assertThatThrownBy(() -> client.fetch(POST_ID))
                .isInstanceOf(AiTraceClient.TraceUnavailableException.class);
    }

    @Test
    @DisplayName("10 §4.7 없는 경로 404 {\"detail\"} 는 trace 없음이 아니다 → TraceUnavailableException")
    void unknownPath404Throws() {
        server.expect(requestTo(TRACE_URL))
                .andRespond(withStatus(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"detail\": \"Not Found\"}"));

        assertThatThrownBy(() -> client.fetch(POST_ID))
                .isInstanceOf(AiTraceClient.TraceUnavailableException.class);
    }

    @Test
    @DisplayName("연결 실패 → TraceUnavailableException")
    void connectionFailureThrows() {
        server.expect(requestTo(TRACE_URL))
                .andRespond(withException(new ConnectException("Connection refused")));

        assertThatThrownBy(() -> client.fetch(POST_ID))
                .isInstanceOf(AiTraceClient.TraceUnavailableException.class);
    }

    @Test
    @DisplayName("read 타임아웃 → TraceTimeoutException")
    void readTimeoutThrows() {
        server.expect(requestTo(TRACE_URL))
                .andRespond(withException(new HttpTimeoutException("request timed out")));

        assertThatThrownBy(() -> client.fetch(POST_ID))
                .isInstanceOf(AiTraceClient.TraceTimeoutException.class);
    }

    @Test
    @DisplayName("운영 생성자: 실제 소켓에서 2초 안에 응답이 없으면 TraceTimeoutException, 서비스 토큰 헤더 유지")
    void realReadTimeout() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer slow = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        slow.createContext("/", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        slow.start();
        try {
            AiTraceClient realClient = new AiTraceClient(
                    aiApiRestClient("http://127.0.0.1:" + slow.getAddress().getPort()));

            long started = System.nanoTime();
            assertThatThrownBy(() -> realClient.fetch(POST_ID))
                    .isInstanceOf(AiTraceClient.TraceTimeoutException.class);
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertThat(elapsedMillis).isBetween(1_900L, 6_000L);
            assertThat(authorization.get()).isEqualTo("Bearer test-token");
        } finally {
            release.countDown();
            slow.stop(0);
        }
    }

    @Test
    @DisplayName("운영 생성자: 닫힌 포트 → TraceUnavailableException")
    void realConnectionRefused() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        AiTraceClient realClient = new AiTraceClient(aiApiRestClient("http://127.0.0.1:" + closedPort));

        assertThatThrownBy(() -> realClient.fetch(POST_ID))
                .isInstanceOf(AiTraceClient.TraceUnavailableException.class);
    }
}
