package com.ttegeoji.backend.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 인증은 ServiceTokenFilterTest 몫이라 필터를 끄고 ApiExceptionHandler 본문만 본다. DB 없음.
@WebMvcTest(controllers = InternalErrorBodyTest.ProbeController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({ApiExceptionHandler.class, InternalErrorBodyTest.ProbeController.class})
class InternalErrorBodyTest {

    private static final String SECRET_INPUT = "secret-input-7f3a";
    private static final String INVALID_REQUEST = "{\"code\":\"INVALID_REQUEST\"}";

    @Autowired
    private MockMvc mockMvc;

    record ProbeBody(@NotBlank String name) {
    }

    @RestController
    static class ProbeController {
        @PostMapping("/internal/v1/probe/stale")
        String stale() {
            throw new InternalApiException(HttpStatus.CONFLICT, "STALE_GENERATION");
        }

        @PostMapping("/internal/v1/probe/body")
        String internalBody(@Valid @RequestBody ProbeBody body) {
            return "ok";
        }

        @PostMapping("/api/probe/body")
        String apiBody(@Valid @RequestBody ProbeBody body) {
            return "ok";
        }

        @GetMapping("/api/probe/illegal")
        String illegal() {
            throw new IllegalArgumentException("잘못된 값입니다.");
        }
    }

    @Test
    @DisplayName("10 §4.7 InternalApiException(409, STALE_GENERATION) → 409 {\"code\":\"STALE_GENERATION\"}")
    void internalApiExceptionBody() throws Exception {
        mockMvc.perform(post("/internal/v1/probe/stale"))
                .andExpect(status().isConflict())
                .andExpect(content().json("{\"code\":\"STALE_GENERATION\"}", JsonCompareMode.STRICT));
    }

    @Test
    @DisplayName("10 §4.7 잘못된 JSON → 422 INVALID_REQUEST, 본문에 입력값 없음")
    void malformedJson() throws Exception {
        mockMvc.perform(post("/internal/v1/probe/body")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"" + SECRET_INPUT + "\""))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().json(INVALID_REQUEST, JsonCompareMode.STRICT))
                .andExpect(content().string(not(containsString(SECRET_INPUT))));
    }

    @Test
    @DisplayName("10 §4.7 @Valid 실패 → 422 INVALID_REQUEST, 본문에 입력값·검증 원문 없음")
    void validationFailure() throws Exception {
        mockMvc.perform(post("/internal/v1/probe/body")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \" \", \"extra\": \"" + SECRET_INPUT + "\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().json(INVALID_REQUEST, JsonCompareMode.STRICT))
                .andExpect(content().string(not(containsString(SECRET_INPUT))));
    }

    @Test
    @DisplayName("/api/** IllegalArgumentException 은 여전히 400 {\"message\"}")
    void publicApiMessageBodyUnchanged() throws Exception {
        mockMvc.perform(get("/api/probe/illegal"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"message\":\"잘못된 값입니다.\"}", JsonCompareMode.STRICT));
    }

    @Test
    @DisplayName("/api/** 잘못된 JSON 은 422 로 바뀌지 않는다(기존 Spring 기본 400)")
    void publicApiMalformedJsonUnchanged() throws Exception {
        mockMvc.perform(post("/api/probe/body")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": "))
                .andExpect(status().isBadRequest());
    }
}
