package com.ttegeoji.backend.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

// 백엔드 → AI API(10 §4·§16.2). 호출 클래스는 ai/ 패키지가 이 빈을 주입받아 만든다.
@Configuration
@EnableConfigurationProperties(GeojiProperties.class)
public class AiApiClientConfig {

    // connect 0.5초(10 §4.7 공통). read 타임아웃은 엔드포인트별이라 호출 클래스가 정한다.
    static final Duration CONNECT_TIMEOUT = Duration.ofMillis(500);

    @Bean
    public RestClient aiApiRestClient(GeojiProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        RestClient.Builder builder = RestClient.builder()
                .requestFactory(new JdkClientHttpRequestFactory(httpClient));

        String baseUrl = properties.ai().baseUrl();
        if (baseUrl != null && !baseUrl.isBlank()) {
            builder.baseUrl(baseUrl);
        }
        // 토큰이 비면 헤더를 붙이지 않는다. AI API 가 401 로 거부한다(10 §4.7)
        String token = properties.internal().serviceToken();
        if (token != null && !token.isBlank()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return builder.build();
    }
}
