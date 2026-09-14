package com.ttegeoji.backend.config;

import com.ttegeoji.backend.support.PostgresContainerSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 실제 SecurityConfig 두 체인과 actuator 를 같이 보려고 전체 컨텍스트(컨테이너)로 띄운다.
@SpringBootTest
@AutoConfigureMockMvc
@Import(ServiceTokenFilterTest.ProbeController.class)
class ServiceTokenFilterTest extends PostgresContainerSupport {

    private static final String UNAUTHORIZED = "{\"code\":\"UNAUTHORIZED\"}";

    @Autowired
    private MockMvc mockMvc;

    // 공개 체인의 issuer-uri 디코더가 Supabase 로 나가지 않게 막는다. 어떤 토큰이든 JWT 로는 무효다.
    @MockitoBean
    private JwtDecoder jwtDecoder;

    @RestController
    static class ProbeController {
        @GetMapping("/internal/v1/probe")
        String internal() {
            return "ok";
        }

        @GetMapping("/api/probe")
        String api() {
            return "ok";
        }
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("10 §4.7 토큰 없음 → 401 UNAUTHORIZED")
    void missingToken() throws Exception {
        mockMvc.perform(get("/internal/v1/probe"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(content().json(UNAUTHORIZED, JsonCompareMode.STRICT));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Bearer wrong-token", "Bearer test-token-extra", "Bearer ", "Basic test-token", "test-token"})
    @DisplayName("10 §4.7 틀린 토큰·틀린 스킴 → 401 UNAUTHORIZED")
    void wrongToken(String authorization) throws Exception {
        mockMvc.perform(get("/internal/v1/probe").header(HttpHeaders.AUTHORIZATION, authorization))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json(UNAUTHORIZED, JsonCompareMode.STRICT));
    }

    @Test
    @DisplayName("10 §4.7 맞는 토큰 → 통과")
    void correctToken() throws Exception {
        mockMvc.perform(get("/internal/v1/probe").header(HttpHeaders.AUTHORIZATION, "Bearer test-token"))
                .andExpect(status().isOk())
                .andExpect(content().string("ok"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    @DisplayName("10 §4.7 설정값이 비었으면 맞는 헤더도 401 — 열어 두지 않는다")
    void emptyConfiguredToken(String configured) throws Exception {
        ServiceTokenFilter filter = new ServiceTokenFilter(new GeojiProperties(
                new GeojiProperties.Internal(configured), new GeojiProperties.Ai(null), null));

        for (String authorization : new String[]{"Bearer " + configured, "Bearer ", "Bearer test-token"}) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/internal/v1/probe");
            request.addHeader(HttpHeaders.AUTHORIZATION, authorization);
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(request, response, chain);

            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(response.getContentAsString()).isEqualTo(UNAUTHORIZED);
            assertThat(chain.getRequest()).as("다음 필터로 넘어가지 않는다").isNull();
        }

        ServiceTokenFilter unset = new ServiceTokenFilter(new GeojiProperties(
                new GeojiProperties.Internal(null), new GeojiProperties.Ai(null), null));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/internal/v1/probe");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer ");
        MockHttpServletResponse response = new MockHttpServletResponse();
        unset.doFilter(request, response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("10 §4.7 /api/** 는 JWT 체인 그대로 — 서비스 토큰으로 통과하지 못한다")
    void publicApiIgnoresServiceToken() throws Exception {
        given(jwtDecoder.decode(anyString())).willThrow(new BadJwtException("invalid"));

        mockMvc.perform(get("/api/probe").header(HttpHeaders.AUTHORIZATION, "Bearer test-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));
        mockMvc.perform(get("/api/probe"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("/actuator/health 는 무인증")
    void actuatorHealthIsOpen() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }
}
