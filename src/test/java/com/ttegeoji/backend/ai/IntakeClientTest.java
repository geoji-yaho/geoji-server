package com.ttegeoji.backend.ai;

import com.sun.net.httpserver.HttpServer;
import com.ttegeoji.backend.ai.IntakeClient.IntakeRequest;
import com.ttegeoji.backend.ai.IntakeClient.IntakeResult;
import com.ttegeoji.backend.ai.IntakeClient.Mode;
import com.ttegeoji.backend.ai.IntakeClient.RejectedException;
import com.ttegeoji.backend.ai.IntakeClient.Status;
import com.ttegeoji.backend.config.AiApiClientConfig;
import com.ttegeoji.backend.config.GeojiProperties;
import com.ttegeoji.backend.domain.enums.IntakeSource;
import com.ttegeoji.backend.domain.enums.PostType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

// 실제 AI API 를 부르지 않는다. MockRestServiceServer 와 로컬 HttpServer 만 쓴다(testing 룰)
class IntakeClientTest {

    private static final String BASE = "http://ai.test";
    private static final String TOKEN = "test-token";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MockRestServiceServer server;
    private IntakeClient client;

    private static GeojiProperties properties(String baseUrl, String token) {
        return new GeojiProperties(new GeojiProperties.Internal(token), new GeojiProperties.Ai(baseUrl), "guardrail-v2", null);
    }

    @BeforeEach
    void setUp() {
        // W1 aiApiRestClient 빈 설정(baseUrl·Authorization)을 그대로 쓰고 요청 팩토리만 목으로 바꾼다
        RestClient.Builder builder = new AiApiClientConfig().aiApiRestClient(properties(BASE, TOKEN)).mutate();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new IntakeClient(builder.build(), true);
    }

    private static IntakeRequest request(Mode mode) {
        return new IntakeRequest(UUID.randomUUID().toString(), "hash-1", mode, PostType.spent, 4800,
                "카페/간식", "아이스 아메리카노", null);
    }

    private static final String PASS_BODY = """
            {"schema_version":1,"mode":"INITIAL","status":"NEEDS_CLARIFICATION",
             "item_review":{"status":"VAGUE","suggested_item":"커피 한 잔"},
             "message":"무엇을 샀는지 조금 더 알려주세요",
             "category_review":{"status":"MISMATCH","suggested_category":"식비","confidence":0.8},
             "injection_detected":false,"intake_source":"AI"}
            """;

    @Test
    @DisplayName("10 §4 200 IntakeResult 파싱")
    void parsesSuccess() {
        server.expect(requestTo(BASE + IntakeClient.PATH))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(PASS_BODY, MediaType.APPLICATION_JSON));

        IntakeResult result = client.call(request(Mode.INITIAL));

        assertThat(result.mode()).isEqualTo(Mode.INITIAL);
        assertThat(result.status()).isEqualTo(Status.NEEDS_CLARIFICATION);
        assertThat(result.itemReview().status()).isEqualTo("VAGUE");
        assertThat(result.itemReview().suggestedItem()).isEqualTo("커피 한 잔");
        assertThat(result.message()).isEqualTo("무엇을 샀는지 조금 더 알려주세요");
        assertThat(result.categoryReview().suggestedCategory()).isEqualTo("식비");
        assertThat(result.categoryReview().confidence()).isEqualTo(0.8);
        assertThat(result.injectionDetected()).isFalse();
        assertThat(result.intakeSource()).isEqualTo(IntakeSource.AI);
        server.verify();
    }

    @Test
    @DisplayName("10 §4.7 요청 헤더 Authorization: Bearer <SERVICE_AUTH_TOKEN>")
    void sendsBearerToken() {
        server.expect(requestTo(BASE + IntakeClient.PATH))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andExpect(header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE))
                .andRespond(withSuccess(PASS_BODY, MediaType.APPLICATION_JSON));

        client.call(request(Mode.INITIAL));
        server.verify();
    }

    @Test
    @DisplayName("10 §4 요청 본문은 intake-v1 IntakeRequest 9 키뿐, 알 수 없는 필드 없음")
    void requestBodyHasOnlySchemaKeys() {
        AtomicReference<String> body = new AtomicReference<>();
        server.expect(requestTo(BASE + IntakeClient.PATH))
                .andExpect(req -> body.set(((MockClientHttpRequest) req).getBodyAsString()))
                .andRespond(withSuccess(PASS_BODY.replace("\"INITIAL\"", "\"FINAL_CHECK\"")
                        .replace("NEEDS_CLARIFICATION", "PASS"), MediaType.APPLICATION_JSON));

        IntakeRequest sent = request(Mode.FINAL_CHECK);
        client.call(sent);

        JsonNode json = MAPPER.readTree(body.get());
        assertThat(json.propertyNames()).containsExactlyInAnyOrder("schema_version", "submission_id",
                "payload_hash", "mode", "post_type", "amount_krw", "category", "item", "reason");
        assertThat(json.get("schema_version").asInt()).isEqualTo(1);
        assertThat(json.get("submission_id").stringValue()).isEqualTo(sent.submissionId());
        assertThat(json.get("mode").stringValue()).isEqualTo("FINAL_CHECK");
        assertThat(json.get("post_type").stringValue()).isEqualTo("spent");
        assertThat(json.get("amount_krw").asInt()).isEqualTo(4800);
        assertThat(json.get("reason").isNull()).isTrue();
    }

    @Test
    @DisplayName("10 §4 503 → FALLBACK(status=PASS·intake_source=FALLBACK)")
    void serverErrorFallsBack() {
        server.expect(requestTo(BASE + IntakeClient.PATH)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        IntakeResult result = client.call(request(Mode.INITIAL));

        assertThat(result).isEqualTo(IntakeResult.fallback(Mode.INITIAL));
        assertThat(result.status()).isEqualTo(Status.PASS);
        assertThat(result.intakeSource()).isEqualTo(IntakeSource.FALLBACK);
        assertThat(result.message()).isEmpty();
        assertThat(result.categoryReview().confidence()).isZero();
    }

    @Test
    @DisplayName("10 §4 연결 실패 → FALLBACK")
    void connectionFailureFallsBack() {
        server.expect(requestTo(BASE + IntakeClient.PATH)).andRespond(withException(new ConnectException("refused")));

        assertThat(client.call(request(Mode.FINAL_CHECK))).isEqualTo(IntakeResult.fallback(Mode.FINAL_CHECK));
    }

    @Test
    @DisplayName("10 §4 422 {\"code\":\"ITEM_LENGTH\"} → 예외에 code(최상위에서 읽음)")
    void unprocessableCarriesCode() {
        server.expect(requestTo(BASE + IntakeClient.PATH))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"ITEM_LENGTH\"}"));

        assertThatThrownBy(() -> client.call(request(Mode.INITIAL)))
                .isInstanceOfSatisfying(RejectedException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(422);
                    assertThat(e.getCode()).isEqualTo("ITEM_LENGTH");
                });
    }

    private static final String UNAUTHORIZED_BODY = "{\"code\":\"UNAUTHORIZED\",\"hint\":\"raw-body-marker\"}";

    @Test
    @DisplayName("10 §4.7 401 {\"code\":\"UNAUTHORIZED\"} → 예외 없이 FALLBACK")
    void unauthorizedFallsBack() {
        server.expect(requestTo(BASE + IntakeClient.PATH))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(UNAUTHORIZED_BODY));

        assertThat(client.call(request(Mode.INITIAL))).isEqualTo(IntakeResult.fallback(Mode.INITIAL));
        server.verify();
    }

    @Test
    @DisplayName("10 §4.7 403 → 예외 없이 FALLBACK")
    void forbiddenFallsBack() {
        server.expect(requestTo(BASE + IntakeClient.PATH))
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"FORBIDDEN\"}"));

        assertThat(client.call(request(Mode.FINAL_CHECK))).isEqualTo(IntakeResult.fallback(Mode.FINAL_CHECK));
        server.verify();
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("10 §4.7 401 → ERROR 로그 1줄에 상태 코드·code 만, 토큰·요청 본문·응답 본문 원문 없음")
    void unauthorizedLogsStatusAndCodeOnly(CapturedOutput output) {
        server.expect(requestTo(BASE + IntakeClient.PATH))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(UNAUTHORIZED_BODY));

        client.call(request(Mode.INITIAL));

        List<String> errorLines = output.getOut().lines()
                .filter(line -> line.contains("ERROR") && line.contains("intake")).toList();
        assertThat(errorLines).hasSize(1);
        assertThat(errorLines.getFirst()).contains("401").contains("UNAUTHORIZED");
        assertThat(output.getAll())
                .doesNotContain(TOKEN)
                .doesNotContain("아이스 아메리카노")
                .doesNotContain("hash-1")
                .doesNotContain("raw-body-marker")
                .doesNotContain(UNAUTHORIZED_BODY);
    }

    @Test
    @DisplayName("10 §4.7 옛 {\"detail\":{\"code\"}} 모양은 읽지 않는다 → HTTP_<status>")
    void legacyDetailShapeNotRead() {
        server.expect(requestTo(BASE + IntakeClient.PATH))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"detail\":{\"code\":\"ITEM_LENGTH\"}}"));

        assertThatThrownBy(() -> client.call(request(Mode.INITIAL)))
                .isInstanceOfSatisfying(RejectedException.class,
                        e -> assertThat(e.getCode()).isEqualTo("HTTP_422"));
    }

    @Test
    @DisplayName("10 §4 키 없음(AI_API_BASE_URL·SERVICE_AUTH_TOKEN 비어 있음) → 호출 없이 FALLBACK")
    void missingKeysFallBackWithoutCall() {
        RestClient base = new AiApiClientConfig().aiApiRestClient(properties("", ""));
        IntakeClient unconfigured = new IntakeClient(base, properties("", ""));

        assertThat(unconfigured.call(request(Mode.INITIAL))).isEqualTo(IntakeResult.fallback(Mode.INITIAL));
    }

    @Test
    @DisplayName("10 §4 운영 배선: read timeout 5초 초과 → FALLBACK (Authorization 헤더도 실린다)")
    void readTimeoutFallsBack() throws IOException {
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer slow = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        slow.createContext(IntakeClient.PATH, exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            try {
                Thread.sleep(IntakeClient.READ_TIMEOUT.plusSeconds(1).toMillis());
                byte[] bytes = PASS_BODY.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (InterruptedException | IOException ignored) {
                // 클라이언트가 먼저 끊는다
            } finally {
                exchange.close();
            }
        });
        slow.start();
        try {
            GeojiProperties props = properties("http://127.0.0.1:" + slow.getAddress().getPort(), TOKEN);
            IntakeClient wired = new IntakeClient(new AiApiClientConfig().aiApiRestClient(props), props);

            long started = System.nanoTime();
            IntakeResult result = wired.call(request(Mode.INITIAL));
            Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

            assertThat(result).isEqualTo(IntakeResult.fallback(Mode.INITIAL));
            assertThat(elapsed).isGreaterThanOrEqualTo(IntakeClient.READ_TIMEOUT.minusMillis(100))
                    .isLessThan(IntakeClient.READ_TIMEOUT.plusSeconds(1));
            assertThat(authorization.get()).isEqualTo("Bearer " + TOKEN);
        } finally {
            slow.stop(0);
        }
    }

    @Test
    @DisplayName("10 §4 toMap 은 intake-v1 IntakeResult 키 그대로")
    void toMapMatchesSchemaKeys() {
        Map<String, Object> map = IntakeResult.fallback(Mode.INITIAL).toMap();
        assertThat(map).containsOnlyKeys("schema_version", "mode", "status", "item_review", "message",
                "category_review", "injection_detected", "intake_source");
    }
}
