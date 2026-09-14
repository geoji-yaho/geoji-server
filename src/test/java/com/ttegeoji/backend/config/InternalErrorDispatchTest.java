package com.ttegeoji.backend.config;

import com.ttegeoji.backend.support.PostgresContainerSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

// MockMvc 는 /error 디스패치를 하지 않는다. 실제 Tomcat 으로 띄워 인증된 내부 요청의 5xx·404 가
// 401 로 바뀌지 않는지 본다(10 §4.7 워커는 5xx 만 재전송한다).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(InternalErrorDispatchTest.ProbeController.class)
class InternalErrorDispatchTest extends PostgresContainerSupport {

    @Value("${local.server.port}")
    private int port;

    private final HttpClient client = HttpClient.newHttpClient();

    @RestController
    static class ProbeController {
        @PostMapping("/internal/v1/probe/boom")
        String boom() {
            throw new RuntimeException("boom");
        }
    }

    private HttpResponse<String> post(String path, String authorization) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .POST(HttpRequest.BodyPublishers.noBody());
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("10 §4.7 인증된 내부 요청의 처리 안 된 예외 → 500 그대로(401 아님)")
    void authorizedServerErrorStays5xx() throws Exception {
        assertThat(post("/internal/v1/probe/boom", "Bearer test-token").statusCode()).isEqualTo(500);
    }

    @Test
    @DisplayName("10 §4.7 인증된 내부 요청의 없는 경로 → 404(401 아님)")
    void authorizedUnknownPathIs404() throws Exception {
        assertThat(post("/internal/v1/does-not-exist", "Bearer test-token").statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("10 §4.7 실제 서버에서도 토큰 없음 → 401 UNAUTHORIZED")
    void unauthorizedOnRealServer() throws Exception {
        HttpResponse<String> response = post("/internal/v1/probe/boom", null);
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).isEqualTo("{\"code\":\"UNAUTHORIZED\"}");
    }
}
