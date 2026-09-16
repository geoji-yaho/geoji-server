package com.ttegeoji.backend.config;

import com.ttegeoji.backend.ai.AiTraceClient;
import com.ttegeoji.backend.ai.IntakeClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

// 10 §16.6-5: AI API(Uvicorn)로 h2c 업그레이드를 시도하면 요청 파싱이 깨진다.
// 세 클라이언트가 HTTP/1.1 로 고정됐는지, 실제 요청 라인으로 확인한다.
class AiHttpVersionTest {

    /** 요청 첫 줄만 읽고 200 을 돌려주는 최소 서버. 반환값은 클라이언트가 보낸 요청 라인 */
    private static CompletableFuture<String> firstRequestLine(ServerSocket server) {
        return CompletableFuture.supplyAsync(() -> {
            try (Socket socket = server.accept()) {
                var reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                String requestLine = reader.readLine();
                // 헤더를 다 비워야 클라이언트가 응답을 기다린다
                String line;
                while ((line = reader.readLine()) != null && !line.isEmpty()) {
                    // 본문은 읽지 않는다. 요청 라인만 필요하다
                }
                socket.getOutputStream().write(
                        "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nContent-Type: application/json\r\n\r\n{}"
                                .getBytes(StandardCharsets.UTF_8));
                socket.getOutputStream().flush();
                return requestLine;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static String requestLineFor(HttpClient client) throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetSocketAddress.createUnresolved("localhost", 0)
                .getAddress())) {
            CompletableFuture<String> captured = firstRequestLine(server);
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create("http://localhost:" + server.getLocalPort() + "/health/live"))
                    .GET()
                    .build();
            client.send(request, HttpResponse.BodyHandlers.ofString());
            return captured.get();
        }
    }

    @Test
    @DisplayName("aiApiRestClient 의 HttpClient 는 HTTP/1.1 로 요청한다")
    void restClientUsesHttp11() throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(AiApiClientConfig.CONNECT_TIMEOUT)
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        assertThat(client.version()).isEqualTo(HttpClient.Version.HTTP_1_1);
        assertThat(requestLineFor(client)).endsWith("HTTP/1.1");
    }

    @Test
    @DisplayName("IntakeClient·AiTraceClient 의 기본 JDK 클라이언트(HTTP_2)와 달라야 한다 — 회귀 방지")
    void defaultClientWouldNegotiateHttp2() {
        assertThat(HttpClient.newBuilder().build().version()).isEqualTo(HttpClient.Version.HTTP_2);
        assertThat(IntakeClient.class).isNotNull();
        assertThat(AiTraceClient.class).isNotNull();
    }
}
