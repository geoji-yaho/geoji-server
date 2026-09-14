package com.ttegeoji.backend.ai;

import com.ttegeoji.backend.config.GeojiProperties;
import com.ttegeoji.backend.domain.enums.IntakeSource;
import com.ttegeoji.backend.domain.enums.PostType;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.UncheckedIOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 백엔드 → AI API 심문관 호출(10 §4). 정본 모양은 AI 저장소 contracts/intake-v1.schema.json.
 * 연결 실패·timeout·5xx·키 없음은 등록을 막지 않고 FALLBACK 결과를 돌려준다(AI API 의 200 FALLBACK 행과 같은 값).
 * 4xx 는 본문 최상위 {"code"} 를 읽어 {@link RejectedException} 으로 던진다(10 §4.7).
 * DB 트랜잭션 안에서 부르지 않는다(10 §2). 로그에 토큰·사유 원문을 남기지 않는다.
 */
@Slf4j
@Component
public class IntakeClient {

    static final String PATH = "/internal/v1/intake";
    // 10 §4 타임아웃 5초. connect 는 10 §4.7 공통 0.5초
    static final Duration READ_TIMEOUT = Duration.ofSeconds(5);
    static final Duration CONNECT_TIMEOUT = Duration.ofMillis(500);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestClient restClient;
    private final boolean configured;

    @Autowired
    public IntakeClient(@Qualifier("aiApiRestClient") RestClient aiApiRestClient, GeojiProperties properties) {
        this(withReadTimeout(aiApiRestClient), isConfigured(properties));
    }

    // 테스트가 MockRestServiceServer 에 묶은 RestClient 를 그대로 쓴다
    IntakeClient(RestClient restClient, boolean configured) {
        this.restClient = restClient;
        this.configured = configured;
    }

    private static RestClient withReadTimeout(RestClient base) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(READ_TIMEOUT);
        // mutate 는 baseUrl·Authorization 기본 헤더를 유지한다
        return base.mutate().requestFactory(factory).build();
    }

    private static boolean isConfigured(GeojiProperties properties) {
        String baseUrl = properties.ai().baseUrl();
        String token = properties.internal().serviceToken();
        return baseUrl != null && !baseUrl.isBlank() && token != null && !token.isBlank();
    }

    public IntakeResult call(IntakeRequest request) {
        if (!configured) {
            log.warn("intake 키 없음(AI_API_BASE_URL·SERVICE_AUTH_TOKEN) → FALLBACK");
            return IntakeResult.fallback(request.mode());
        }
        String body = MAPPER.writeValueAsString(request.toBody());
        try {
            return restClient.post()
                    .uri(PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .exchange((req, res) -> {
                        int status = res.getStatusCode().value();
                        String text = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
                        return handle(request.mode(), status, text);
                    });
        } catch (RejectedException e) {
            throw e;
        } catch (RestClientException | UncheckedIOException e) {
            // 연결 실패·read timeout 은 ResourceAccessException 으로 온다
            log.warn("intake 호출 실패 → FALLBACK: {}", e.getClass().getSimpleName());
            return IntakeResult.fallback(request.mode());
        }
    }

    private static IntakeResult handle(Mode mode, int status, String text) {
        if (status >= 200 && status < 300) {
            try {
                return IntakeResult.parse(MAPPER.readTree(text));
            } catch (JacksonException | IllegalArgumentException e) {
                // 계약 밖 200 은 AI API 실패와 같게 본다. 본문은 로그에 싣지 않는다
                log.warn("intake 응답이 계약과 다름 → FALLBACK");
                return IntakeResult.fallback(mode);
            }
        }
        if (status >= 400 && status < 500) {
            throw new RejectedException(status, codeOf(status, text));
        }
        log.warn("intake HTTP {} → FALLBACK", status);
        return IntakeResult.fallback(mode);
    }

    // 거부 본문은 {"code"} 최상위(9/14). 없으면 워커 규약처럼 HTTP_<status>
    private static String codeOf(int status, String text) {
        try {
            JsonNode code = MAPPER.readTree(text).get("code");
            if (code != null && code.isString() && !code.stringValue().isBlank()) {
                return code.stringValue();
            }
        } catch (JacksonException ignored) {
            // 본문이 JSON 이 아니면 아래 기본값
        }
        return "HTTP_" + status;
    }

    public enum Mode {
        INITIAL, FINAL_CHECK
    }

    public enum Status {
        PASS, NEEDS_CLARIFICATION, BLOCKED
    }

    /** IntakeRequest(intake-v1). submission_id·payload_hash 는 응답에 에코되지 않는다(10 §4). */
    public record IntakeRequest(String submissionId, String payloadHash, Mode mode, PostType postType,
                                int amountKrw, String category, String item, String reason) {

        // 스키마 additionalProperties=false. 이 9 키 밖을 넣지 않는다. reason 은 null 도 키가 있어야 한다
        Map<String, Object> toBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("schema_version", 1);
            body.put("submission_id", submissionId);
            body.put("payload_hash", payloadHash);
            body.put("mode", mode.name());
            body.put("post_type", postType.name());
            body.put("amount_krw", amountKrw);
            body.put("category", category);
            body.put("item", item);
            body.put("reason", reason);
            return body;
        }
    }

    public record ItemReview(String status, String suggestedItem) {
    }

    public record CategoryReview(String status, String suggestedCategory, double confidence) {
    }

    /** IntakeResult(intake-v1). */
    public record IntakeResult(Mode mode, Status status, ItemReview itemReview, String message,
                               CategoryReview categoryReview, boolean injectionDetected,
                               IntakeSource intakeSource) {

        /** 10 §4 폴백 행: status=PASS·intake_source=FALLBACK·message=""·category_review{OK, null, 0.0}. */
        public static IntakeResult fallback(Mode mode) {
            return new IntakeResult(mode, Status.PASS, new ItemReview("OK", null), "",
                    new CategoryReview("OK", null, 0.0), false, IntakeSource.FALLBACK);
        }

        /** submissions.intake_result 에 저장한 toMap() JSON 을 다시 읽는다. */
        public static IntakeResult fromJson(String json) {
            if (json == null) {
                throw new IllegalStateException("저장된 intake 결과가 없습니다.");
            }
            try {
                return parse(MAPPER.readTree(json));
            } catch (JacksonException | IllegalArgumentException e) {
                throw new IllegalStateException("저장된 intake 결과를 읽을 수 없습니다.", e);
            }
        }

        static IntakeResult parse(JsonNode node) {
            if (node == null || !node.isObject() || node.path("schema_version").asInt(0) != 1) {
                throw new IllegalArgumentException("schema_version");
            }
            JsonNode item = object(node, "item_review");
            JsonNode category = object(node, "category_review");
            return new IntakeResult(
                    Mode.valueOf(text(node, "mode")),
                    Status.valueOf(text(node, "status")),
                    new ItemReview(text(item, "status"), nullableText(item, "suggested_item")),
                    nullableText(node, "message"),
                    new CategoryReview(text(category, "status"), nullableText(category, "suggested_category"),
                            number(category, "confidence")),
                    bool(node, "injection_detected"),
                    IntakeSource.valueOf(text(node, "intake_source")));
        }

        /** 공개 응답·submissions.intake_result 에 싣는 스키마 모양(snake_case). */
        public Map<String, Object> toMap() {
            Map<String, Object> itemMap = new LinkedHashMap<>();
            itemMap.put("status", itemReview.status());
            itemMap.put("suggested_item", itemReview.suggestedItem());
            Map<String, Object> categoryMap = new LinkedHashMap<>();
            categoryMap.put("status", categoryReview.status());
            categoryMap.put("suggested_category", categoryReview.suggestedCategory());
            categoryMap.put("confidence", categoryReview.confidence());
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("schema_version", 1);
            map.put("mode", mode.name());
            map.put("status", status.name());
            map.put("item_review", itemMap);
            map.put("message", message);
            map.put("category_review", categoryMap);
            map.put("injection_detected", injectionDetected);
            map.put("intake_source", intakeSource.name());
            return map;
        }

        private static JsonNode object(JsonNode node, String field) {
            JsonNode value = node.get(field);
            if (value == null || !value.isObject()) {
                throw new IllegalArgumentException(field);
            }
            return value;
        }

        private static String text(JsonNode node, String field) {
            JsonNode value = node.get(field);
            if (value == null || !value.isString()) {
                throw new IllegalArgumentException(field);
            }
            return value.stringValue();
        }

        private static String nullableText(JsonNode node, String field) {
            JsonNode value = node.get(field);
            if (value == null) {
                throw new IllegalArgumentException(field);
            }
            return value.isNull() ? null : text(node, field);
        }

        private static double number(JsonNode node, String field) {
            JsonNode value = node.get(field);
            if (value == null || !value.isNumber()) {
                throw new IllegalArgumentException(field);
            }
            return value.doubleValue();
        }

        private static boolean bool(JsonNode node, String field) {
            JsonNode value = node.get(field);
            if (value == null || !value.isBoolean()) {
                throw new IllegalArgumentException(field);
            }
            return value.booleanValue();
        }
    }

    /** AI API 4xx. code 는 본문 최상위 {"code"}(예: ITEM_LENGTH·REASON_LENGTH·AMOUNT·INVALID_REQUEST·UNAUTHORIZED). */
    @Getter
    public static class RejectedException extends RuntimeException {
        private final int status;
        private final String code;

        public RejectedException(int status, String code) {
            super("intake 거부 HTTP " + status + " " + code);
            this.status = status;
            this.code = code;
        }
    }
}
