package com.ttegeoji.backend.verdict;

import com.ttegeoji.backend.config.InternalApiException;
import com.ttegeoji.backend.support.PostgresContainerSupport;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 서비스 트랜잭션을 실제로 커밋한다. 사건마다 새 행이라 서로 섞이지 않는다
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class GenerationFailedServiceTest extends PostgresContainerSupport {

    @Autowired
    private GenerationFailedService service;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MockMvc mockMvc;

    private VerdictFallbackServiceTest.Seed seed;

    @BeforeEach
    void setUp() {
        seed = new VerdictFallbackServiceTest.Seed(jdbc);
    }

    /** SENTENCE 가 begin 해 active generation 이 걸린 PENDING verdict */
    private UUID pendingWithActive(UUID generationId) {
        UUID verdictId = seed.pendingVerdict("guilty", 2, 1, "now() + interval '10 seconds'").verdictId();
        seed.updateVerdict(verdictId, "active_job_id = ?, active_generation_id = ?, text_status = 'GENERATING'",
                UUID.randomUUID(), generationId);
        return verdictId;
    }

    /** 템플릿 노출 뒤 TEXT_RETRY round 가 begin 한 FINAL verdict. mild·hell TEMPLATE 문구 text_version 1 */
    private UUID textRetryWithActive(UUID generationId, int retryRound) {
        UUID verdictId = seed.pendingVerdict("guilty", 2, 1, "now() - interval '1 minute'").verdictId();
        seed.updateVerdict(verdictId, """
                sentence_status = 'FINAL', sentence = CAST('oneDay' AS sentence), sentence_source = 'RULE',
                sentencing_reason = ?, reason_source = 'TEMPLATE', text_status = 'TEMPLATE_READY', text_version = 1,
                retry_round = ?, active_job_id = ?, active_generation_id = ?
                """, VerdictFallbackServiceTest.ONE_DAY_REASON, retryRound, UUID.randomUUID(), generationId);
        for (String intensity : List.of("mild", "hell")) {
            jdbc.update("""
                    INSERT INTO verdict_texts (verdict_id, intensity, headline, statement, source, text_version)
                    VALUES (?, CAST(? AS spice_level), '유죄', CAST('[{"text":"t","kind":"opinion","evidence_labels":[]}]' AS jsonb),
                            'TEMPLATE', 1)
                    """, verdictId, intensity);
        }
        return verdictId;
    }

    private GenerationFailedService.Response fail(UUID verdictId, UUID generationId, String code) {
        return service.fail(verdictId, new GenerationFailedService.Request(UUID.randomUUID(), generationId, code),
                "trace-failed");
    }

    private static void assertRejected(ThrowingCallable call, HttpStatus status, String code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(InternalApiException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(status);
            assertThat(e.getCode()).isEqualTo(code);
        });
    }

    private static double retryInSeconds(Map<String, Object> verdict) {
        return ((Number) verdict.get("retry_in_seconds")).doubleValue();
    }

    @Test
    @DisplayName("10 §4.6 AI_NOT_READY(1행) → FINAL/RULE·TEMPLATE_READY·RETAIN 1개·round 예약 없음·active 해제")
    void immediateFallback() {
        UUID generationId = UUID.randomUUID();
        UUID verdictId = pendingWithActive(generationId);

        assertThat(fail(verdictId, generationId, "AI_NOT_READY").verdictId()).isEqualTo(verdictId);

        Map<String, Object> verdict = seed.verdict(verdictId);
        assertThat(verdict.get("sentence_status")).isEqualTo("FINAL");
        assertThat(verdict.get("sentence")).isEqualTo("oneDay");
        assertThat(verdict.get("sentence_source")).isEqualTo("RULE");
        assertThat(verdict.get("reason_source")).isEqualTo("TEMPLATE");
        assertThat(verdict.get("text_status")).isEqualTo("TEMPLATE_READY");
        assertThat(verdict.get("retry_round")).isEqualTo(0);
        assertThat(verdict.get("pending_retry_at")).isNull();
        assertThat(verdict.get("active_job_id")).isNull();
        assertThat(verdict.get("active_generation_id")).isNull();
        assertThat(verdict.get("last_failed_generation_id")).isEqualTo(generationId);
        assertThat(verdict.get("last_failed_code")).isEqualTo("AI_NOT_READY");
        assertThat(seed.texts(verdictId)).hasSize(2);
        assertThat(seed.retainCount(verdictId)).isEqualTo(1);
    }

    @Test
    @DisplayName("10 §4.6 VENDOR_UNAVAILABLE(2행) → 같은 폴백 + retry_round 1·pending_retry_at ≈ DB now()+5분")
    void fallbackWithRoundOne() {
        UUID generationId = UUID.randomUUID();
        UUID verdictId = pendingWithActive(generationId);

        fail(verdictId, generationId, "VENDOR_UNAVAILABLE");

        Map<String, Object> verdict = seed.verdict(verdictId);
        assertThat(verdict.get("sentence_status")).isEqualTo("FINAL");
        assertThat(verdict.get("text_status")).isEqualTo("TEMPLATE_READY");
        assertThat(verdict.get("retry_round")).isEqualTo(1);
        assertThat(retryInSeconds(verdict)).isBetween(290.0, 300.0);
        assertThat(seed.retainCount(verdictId)).isEqualTo(1);
    }

    @ParameterizedTest(name = "{0} → round 예약 {1}")
    @CsvSource({
            "AI_NOT_READY, false", "POLICY_ERROR, false", "EVIDENCE_INVALIDATED, false",
            "VENDOR_UNAVAILABLE, true", "BUDGET_EXCEEDED, true", "EVAL_FAILED, true", "SCHEMA_INVALID, true",
            "DEADLINE_EXCEEDED, true"})
    @DisplayName("10 §4.6 코드 표 8종 — 모두 폴백, 1행은 round 없음·2행은 round 1")
    void codeTable(String code, boolean schedulesRound) {
        UUID generationId = UUID.randomUUID();
        UUID verdictId = pendingWithActive(generationId);

        fail(verdictId, generationId, code);

        Map<String, Object> verdict = seed.verdict(verdictId);
        assertThat(verdict.get("sentence_status")).isEqualTo("FINAL");
        assertThat(verdict.get("text_status")).isEqualTo("TEMPLATE_READY");
        assertThat(seed.retainCount(verdictId)).isEqualTo(1);
        assertThat(verdict.get("retry_round")).isEqualTo(schedulesRound ? 1 : 0);
        assertThat(verdict.get("pending_retry_at") != null).isEqualTo(schedulesRound);
    }

    @Test
    @DisplayName("10 §4.6 같은 generation·같은 코드 재전송 → 200, RETAIN 여전히 1개·예약 시각 그대로")
    void retransmitAccepted() {
        UUID generationId = UUID.randomUUID();
        UUID verdictId = pendingWithActive(generationId);

        fail(verdictId, generationId, "VENDOR_UNAVAILABLE");
        Object pendingRetryAt = seed.verdict(verdictId).get("pending_retry_at");
        assertThat(fail(verdictId, generationId, "VENDOR_UNAVAILABLE").verdictId()).isEqualTo(verdictId);

        assertThat(seed.retainCount(verdictId)).isEqualTo(1);
        Map<String, Object> verdict = seed.verdict(verdictId);
        assertThat(verdict.get("retry_round")).isEqualTo(1);
        assertThat(verdict.get("pending_retry_at")).isEqualTo(pendingRetryAt);
    }

    @Test
    @DisplayName("10 §7 TEXT_RETRY round 1 실패 → round 2 예약(+10분)")
    void textRetryRoundOneFails() {
        UUID generationId = UUID.randomUUID();
        UUID verdictId = textRetryWithActive(generationId, 1);

        fail(verdictId, generationId, "SCHEMA_INVALID");

        Map<String, Object> verdict = seed.verdict(verdictId);
        assertThat(verdict.get("retry_round")).isEqualTo(2);
        assertThat(retryInSeconds(verdict)).isBetween(590.0, 600.0);
    }

    @Test
    @DisplayName("10 §4.6 현재 generation 아님 → 409 STALE_GENERATION, 상태 불변")
    void otherGenerationRejected() {
        UUID generationId = UUID.randomUUID();
        UUID verdictId = pendingWithActive(generationId);

        assertRejected(() -> fail(verdictId, UUID.randomUUID(), "VENDOR_UNAVAILABLE"),
                HttpStatus.CONFLICT, "STALE_GENERATION");
        assertThat(seed.verdict(verdictId).get("sentence_status")).isEqualTo("PENDING");
        assertThat(seed.retainCount(verdictId)).isZero();
    }

    @Test
    @DisplayName("10 §4.6 코드 표 밖 → 422 INVALID_REQUEST(HTTP 본문 {\"code\"})")
    void unknownCodeRejected() throws Exception {
        UUID generationId = UUID.randomUUID();
        UUID verdictId = pendingWithActive(generationId);

        assertRejected(() -> fail(verdictId, generationId, "LEASE_EXPIRED"),
                HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_REQUEST");
        mockMvc.perform(post("/internal/v1/verdicts/{id}/generation-failed", verdictId)
                        .header("Authorization", "Bearer test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"job_id\":\"%s\",\"generation_id\":\"%s\",\"error_code\":\"NOPE\"}"
                                .formatted(UUID.randomUUID(), generationId)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().json("{\"code\":\"INVALID_REQUEST\"}", JsonCompareMode.STRICT));
        assertThat(seed.verdict(verdictId).get("sentence_status")).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("10 §4.6·§7 TEXT_RETRY round 2 실패 → round 3 예약(+20분), 문구·형량 불변, RETAIN 없음")
    void textRetryRoundTwoFails() {
        UUID generationId = UUID.randomUUID();
        UUID verdictId = textRetryWithActive(generationId, 2);
        List<Map<String, Object>> textsBefore = seed.texts(verdictId);

        fail(verdictId, generationId, "EVAL_FAILED");

        Map<String, Object> verdict = seed.verdict(verdictId);
        assertThat(verdict.get("retry_round")).isEqualTo(3);
        assertThat(retryInSeconds(verdict)).isBetween(1190.0, 1200.0);
        assertThat(verdict.get("active_job_id")).isNull();
        assertThat(verdict.get("active_generation_id")).isNull();
        assertThat(verdict.get("text_status")).isEqualTo("TEMPLATE_READY");
        assertThat(((Number) verdict.get("text_version")).longValue()).isEqualTo(1L);
        assertThat(verdict.get("sentence")).isEqualTo("oneDay");
        assertThat(verdict.get("sentencing_reason")).isEqualTo(VerdictFallbackServiceTest.ONE_DAY_REASON);
        assertThat(seed.texts(verdictId)).isEqualTo(textsBefore);
        assertThat(seed.retainCount(verdictId)).isZero();
    }

    @Test
    @DisplayName("10 §4.6·§7 TEXT_RETRY round 3 실패 → 예약 없음 + WARN 운영 알림")
    void lastRoundFails(CapturedOutput output) {
        UUID generationId = UUID.randomUUID();
        UUID verdictId = textRetryWithActive(generationId, 3);
        List<Map<String, Object>> textsBefore = seed.texts(verdictId);

        fail(verdictId, generationId, "DEADLINE_EXCEEDED");

        Map<String, Object> verdict = seed.verdict(verdictId);
        assertThat(verdict.get("retry_round")).isEqualTo(3);
        assertThat(verdict.get("pending_retry_at")).isNull();
        assertThat(verdict.get("active_generation_id")).isNull();
        assertThat(seed.texts(verdictId)).isEqualTo(textsBefore);
        assertThat(output.getAll()).contains("WARN").contains("운영 알림").contains(verdictId.toString());
    }
}
