package com.ttegeoji.backend.ai;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

import java.net.SocketTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * 백엔드 → AI API trace 조회(10 §4.5·§16.2). 응답은 라벨·코드·개수·시각뿐이고 원문이 없다(AI 쪽 계약).
 * 권한 검증은 부르는 쪽이 먼저 한다. 이 클래스는 post_id 를 그대로 넘긴다.
 */
@Component
public class AiTraceClient {

    static final String TRACE_NOT_FOUND = "TRACE_NOT_FOUND";

    // connect 0.5초는 10 §4.7 공통(W1 aiApiRestClient 와 같은 값), read 2초는 사용자 9/15 결정
    static final Duration CONNECT_TIMEOUT = Duration.ofMillis(500);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(2);

    private final RestClient restClient;

    // W1 빈의 baseUrl·Authorization 기본 헤더는 그대로 두고 request factory 만 이 클라이언트 전용으로 바꾼다
    @Autowired
    public AiTraceClient(@Qualifier("aiApiRestClient") RestClient aiApiRestClient) {
        this(aiApiRestClient.mutate().requestFactory(requestFactory()));
    }

    // 테스트가 MockRestServiceServer 를 붙인 builder 를 넘긴다
    AiTraceClient(RestClient.Builder builder) {
        this.restClient = builder.build();
    }

    private static JdkClientHttpRequestFactory requestFactory() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(READ_TIMEOUT);
        return factory;
    }

    /**
     * @return trace JSON(AI API 모양 그대로). 기록이 없으면(404 TRACE_NOT_FOUND) empty
     * @throws TraceTimeoutException     read 타임아웃
     * @throws TraceUnavailableException 5xx(503 DB_UNAVAILABLE 포함)·연결 실패·그 밖 4xx·본문 이상
     */
    public Optional<JsonNode> fetch(UUID postId) {
        try {
            return restClient.get()
                    .uri("/internal/v1/trials/{postId}/trace", postId)
                    .exchange((request, response) -> {
                        HttpStatusCode status = response.getStatusCode();
                        if (status.is2xxSuccessful()) {
                            JsonNode body = response.bodyTo(JsonNode.class);
                            if (body == null || !body.isObject()) {
                                throw new TraceUnavailableException("AI API trace 응답 본문이 객체가 아니다");
                            }
                            return Optional.of(body);
                        }
                        // 없는 경로 404 는 FastAPI 기본 {"detail"} 이라 code 로 가른다(10 §4.7)
                        if (status.value() == 404 && TRACE_NOT_FOUND.equals(codeOf(response))) {
                            return Optional.<JsonNode>empty();
                        }
                        throw new TraceUnavailableException("AI API trace 거부 HTTP " + status.value());
                    });
        } catch (TraceUnavailableException e) {
            throw e;
        } catch (ResourceAccessException e) {
            if (isReadTimeout(e)) {
                throw new TraceTimeoutException(e);
            }
            throw new TraceUnavailableException("AI API trace 연결 실패", e);
        } catch (RestClientException e) {
            throw new TraceUnavailableException("AI API trace 응답 처리 실패", e);
        }
    }

    // 거부 본문은 최상위 {"code"}(10 §4.7). JSON 이 아니면 코드 없음으로 본다
    private static String codeOf(RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response) {
        try {
            JsonNode body = response.bodyTo(JsonNode.class);
            JsonNode code = body == null ? null : body.get("code");
            return code != null && code.isString() ? code.asString() : null;
        } catch (RestClientException e) {
            return null;
        }
    }

    // connect 타임아웃은 연결 실패(502) 쪽이다. HttpConnectTimeoutException 은 HttpTimeoutException 의 하위라 먼저 본다
    private static boolean isReadTimeout(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpConnectTimeoutException) {
                return false;
            }
            if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) {
                return true;
            }
        }
        return false;
    }

    /** AI API 가 trace 를 주지 못했다. 공개 응답 502 */
    public static class TraceUnavailableException extends RuntimeException {
        public TraceUnavailableException(String message) {
            super(message);
        }

        public TraceUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** AI API 가 read 타임아웃 안에 응답하지 않았다. 공개 응답 504 */
    public static class TraceTimeoutException extends RuntimeException {
        public TraceTimeoutException(Throwable cause) {
            super("AI API trace read 타임아웃", cause);
        }
    }
}
