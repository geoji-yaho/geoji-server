package com.ttegeoji.backend.verdict;

import com.ttegeoji.backend.config.InternalApiException;
import com.ttegeoji.backend.domain.enums.ContentSource;
import com.ttegeoji.backend.domain.enums.Sentence;
import com.ttegeoji.backend.jobs.JobKind;
import com.ttegeoji.backend.support.PostgresContainerSupport;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 서비스 트랜잭션을 실제로 커밋한다(잠금·DB now() 를 그대로 본다). 사건마다 새 행이라 서로 섞이지 않는다
@SpringBootTest
@AutoConfigureMockMvc
class BeginGenerationServiceTest extends PostgresContainerSupport {

    private static final String VALID_LEASE = "now() + interval '1 minute'";
    private static final String EXPIRED_LEASE = "now() - interval '1 second'";
    private static final String BEFORE_DEADLINE = "now() + interval '10 seconds'";
    private static final String PAST_DEADLINE = "now() - interval '1 second'";

    @Autowired
    private BeginGenerationService service;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MockMvc mockMvc;

    private VerdictFallbackServiceTest.Seed seed;

    @BeforeEach
    void setUp() {
        seed = new VerdictFallbackServiceTest.Seed(jdbc);
    }

    private UUID pending(String deadlineExpr) {
        return seed.pendingVerdict("guilty", 2, 1, deadlineExpr).verdictId();
    }

    /** 템플릿이 노출된 FINAL(폴백 뒤) */
    private UUID finalTemplate(String textStatus) {
        UUID verdictId = pending(PAST_DEADLINE);
        seed.updateVerdict(verdictId, """
                sentence_status = 'FINAL', sentence = CAST('oneDay' AS sentence), sentence_source = 'RULE',
                sentencing_reason = ?, reason_source = 'TEMPLATE', text_status = ?, text_version = 1, retry_round = 1
                """, VerdictFallbackServiceTest.ONE_DAY_REASON, textStatus);
        return verdictId;
    }

    private static BeginGenerationService.Request request(UUID jobId, UUID generationId) {
        return new BeginGenerationService.Request(jobId, generationId, 1);
    }

    private static void assertRejected(ThrowingCallable call, HttpStatus status, String code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(InternalApiException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(status);
            assertThat(e.getCode()).isEqualTo(code);
        });
    }

    private OffsetDateTime jobDeadline(UUID jobId) {
        return jdbc.queryForObject("SELECT deadline_at FROM ai.jobs WHERE id = ?", OffsetDateTime.class, jobId);
    }

    @Test
    @DisplayName("10 §4.3 PENDING 정상 → fixed_sentencing null·active 설정·GENERATING·deadline_at = verdict 마감")
    void pendingBegins() {
        UUID verdictId = pending(BEFORE_DEADLINE);
        UUID generationId = UUID.randomUUID();
        UUID jobId = seed.runningJob(verdictId, JobKind.SENTENCE, generationId, VALID_LEASE, BEFORE_DEADLINE);

        BeginGenerationService.Response response = service.begin(verdictId, request(jobId, generationId));

        assertThat(response.fixedSentencing()).isNull();
        assertThat(response.textVersion()).isZero();
        Map<String, Object> verdict = seed.verdict(verdictId);
        OffsetDateTime verdictDeadline = jdbc.queryForObject(
                "SELECT deadline_at FROM verdicts WHERE id = ?", OffsetDateTime.class, verdictId);
        assertThat(response.deadlineAt().toInstant()).isEqualTo(verdictDeadline.toInstant());
        assertThat(verdict.get("active_job_id")).isEqualTo(jobId);
        assertThat(verdict.get("active_generation_id")).isEqualTo(generationId);
        assertThat(verdict.get("text_status")).isEqualTo("GENERATING");
    }

    @Test
    @DisplayName("10 §4.3 같은 현재 generation 재호출 허용")
    void sameGenerationRecall() {
        UUID verdictId = pending(BEFORE_DEADLINE);
        UUID generationId = UUID.randomUUID();
        UUID jobId = seed.runningJob(verdictId, JobKind.SENTENCE, generationId, VALID_LEASE, BEFORE_DEADLINE);

        service.begin(verdictId, request(jobId, generationId));
        BeginGenerationService.Response again = service.begin(verdictId, request(jobId, generationId));

        assertThat(again.fixedSentencing()).isNull();
        assertThat(seed.verdict(verdictId).get("active_generation_id")).isEqualTo(generationId);
    }

    @Test
    @DisplayName("10 §4.3 verdict_version 불일치 → 409 STALE_GENERATION")
    void versionMismatch() {
        UUID verdictId = pending(BEFORE_DEADLINE);
        UUID generationId = UUID.randomUUID();
        UUID jobId = seed.runningJob(verdictId, JobKind.SENTENCE, generationId, VALID_LEASE, BEFORE_DEADLINE);

        assertRejected(() -> service.begin(verdictId, new BeginGenerationService.Request(jobId, generationId, 2)),
                HttpStatus.CONFLICT, "STALE_GENERATION");
        assertThat(seed.verdict(verdictId).get("active_job_id")).isNull();
    }

    @Test
    @DisplayName("10 §4.3 다른 활성 generation 이 유효 → 409 STALE_GENERATION")
    void otherActiveValid() {
        UUID verdictId = pending(BEFORE_DEADLINE);
        UUID firstGeneration = UUID.randomUUID();
        UUID firstJob = seed.runningJob(verdictId, JobKind.SENTENCE, firstGeneration, VALID_LEASE, BEFORE_DEADLINE);
        service.begin(verdictId, request(firstJob, firstGeneration));

        UUID secondGeneration = UUID.randomUUID();
        UUID secondJob = seed.runningJob(verdictId, JobKind.SENTENCE, secondGeneration, VALID_LEASE, BEFORE_DEADLINE);

        assertRejected(() -> service.begin(verdictId, request(secondJob, secondGeneration)),
                HttpStatus.CONFLICT, "STALE_GENERATION");
        assertThat(seed.verdict(verdictId).get("active_job_id")).isEqualTo(firstJob);
    }

    @Test
    @DisplayName("10 §4.3 다른 활성 job 의 lease 가 만료됐으면 대체한다")
    void expiredActiveReplaced() {
        UUID verdictId = pending(BEFORE_DEADLINE);
        UUID firstGeneration = UUID.randomUUID();
        UUID firstJob = seed.runningJob(verdictId, JobKind.SENTENCE, firstGeneration, VALID_LEASE, BEFORE_DEADLINE);
        service.begin(verdictId, request(firstJob, firstGeneration));
        jdbc.update("UPDATE ai.jobs SET lease_until = now() - interval '1 second' WHERE id = ?", firstJob);

        UUID secondGeneration = UUID.randomUUID();
        UUID secondJob = seed.runningJob(verdictId, JobKind.SENTENCE, secondGeneration, VALID_LEASE, BEFORE_DEADLINE);
        try {
            service.begin(verdictId, request(secondJob, secondGeneration));

            Map<String, Object> verdict = seed.verdict(verdictId);
            assertThat(verdict.get("active_job_id")).isEqualTo(secondJob);
            assertThat(verdict.get("active_generation_id")).isEqualTo(secondGeneration);
        } finally {
            seed.finishJobs(firstJob);
        }
    }

    @Test
    @DisplayName("10 §4.3 요청 job lease 만료 → 409 STALE_GENERATION")
    void requesterLeaseExpired() {
        UUID verdictId = pending(BEFORE_DEADLINE);
        UUID generationId = UUID.randomUUID();
        UUID jobId = seed.runningJob(verdictId, JobKind.SENTENCE, generationId, EXPIRED_LEASE, BEFORE_DEADLINE);
        try {
            assertRejected(() -> service.begin(verdictId, request(jobId, generationId)),
                    HttpStatus.CONFLICT, "STALE_GENERATION");
            assertThat(seed.verdict(verdictId).get("active_job_id")).isNull();
        } finally {
            seed.finishJobs(jobId);
        }
    }

    @Test
    @DisplayName("10 §4.3 요청 job 이 RUNNING 아님·없음 → 409 STALE_GENERATION")
    void requesterNotRunningOrMissing() {
        UUID verdictId = pending(BEFORE_DEADLINE);
        UUID generationId = UUID.randomUUID();
        UUID jobId = seed.runningJob(verdictId, JobKind.SENTENCE, generationId, VALID_LEASE, BEFORE_DEADLINE);
        seed.finishJobs(jobId);

        assertRejected(() -> service.begin(verdictId, request(jobId, generationId)),
                HttpStatus.CONFLICT, "STALE_GENERATION");
        assertRejected(() -> service.begin(verdictId, request(UUID.randomUUID(), generationId)),
                HttpStatus.CONFLICT, "STALE_GENERATION");
    }

    @Test
    @DisplayName("10 §4.3 다른 판결의 job 으로 begin → 409 STALE_GENERATION")
    void jobOfOtherVerdict() {
        UUID verdictId = pending(BEFORE_DEADLINE);
        UUID otherVerdictId = pending(BEFORE_DEADLINE);
        UUID generationId = UUID.randomUUID();
        UUID otherJob = seed.runningJob(otherVerdictId, JobKind.SENTENCE, generationId, VALID_LEASE, BEFORE_DEADLINE);

        assertRejected(() -> service.begin(verdictId, request(otherJob, generationId)),
                HttpStatus.CONFLICT, "STALE_GENERATION");
    }

    @Test
    @DisplayName("10 §4.3 요청 generation 이 job 과 다름 → 409 STALE_GENERATION")
    void requesterGenerationMismatch() {
        UUID verdictId = pending(BEFORE_DEADLINE);
        UUID jobId = seed.runningJob(verdictId, JobKind.SENTENCE, UUID.randomUUID(), VALID_LEASE, BEFORE_DEADLINE);

        assertRejected(() -> service.begin(verdictId, request(jobId, UUID.randomUUID())),
                HttpStatus.CONFLICT, "STALE_GENERATION");
    }

    @Test
    @DisplayName("10 §4.3 PENDING 인데 DB now() ≥ deadline_at → 409 DEADLINE_EXCEEDED")
    void deadlinePassed() {
        UUID verdictId = pending(PAST_DEADLINE);
        UUID generationId = UUID.randomUUID();
        UUID jobId = seed.runningJob(verdictId, JobKind.SENTENCE, generationId, VALID_LEASE, PAST_DEADLINE);

        assertRejected(() -> service.begin(verdictId, request(jobId, generationId)),
                HttpStatus.CONFLICT, "DEADLINE_EXCEEDED");
        assertThat(seed.verdict(verdictId).get("text_status")).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("10 §4.3 FINAL+TEMPLATE_READY 에 TEXT_RETRY → 고정 형량·text_version·TEXT_RETRY job 마감")
    void textRetryGetsFixedSentencing() {
        UUID verdictId = finalTemplate("TEMPLATE_READY");
        UUID generationId = UUID.randomUUID();
        UUID jobId = seed.runningJob(verdictId, JobKind.TEXT_RETRY, generationId, VALID_LEASE, "now() + interval '20 seconds'");

        BeginGenerationService.Response response = service.begin(verdictId, request(jobId, generationId));

        assertThat(response.fixedSentencing()).isEqualTo(new BeginGenerationService.FixedSentencing(
                Sentence.oneDay, VerdictFallbackServiceTest.ONE_DAY_REASON, ContentSource.TEMPLATE));
        assertThat(response.textVersion()).isEqualTo(1L);
        assertThat(response.deadlineAt().toInstant()).isEqualTo(jobDeadline(jobId).toInstant());
        Map<String, Object> verdict = seed.verdict(verdictId);
        assertThat(verdict.get("active_job_id")).isEqualTo(jobId);
        assertThat(verdict.get("text_status")).isEqualTo("TEMPLATE_READY");
    }

    @Test
    @DisplayName("10 §4.3 AI_READY 에 TEXT_RETRY → 409 STALE_GENERATION")
    void textRetryOnAiReady() {
        UUID verdictId = finalTemplate("AI_READY");
        UUID generationId = UUID.randomUUID();
        UUID jobId = seed.runningJob(verdictId, JobKind.TEXT_RETRY, generationId, VALID_LEASE, "now() + interval '20 seconds'");

        assertRejected(() -> service.begin(verdictId, request(jobId, generationId)),
                HttpStatus.CONFLICT, "STALE_GENERATION");
    }

    @Test
    @DisplayName("10 §13 작업 8 템플릿 이후 늦게 온 SENTENCE begin → 409 STALE_GENERATION")
    void lateSentenceAfterTemplate() {
        UUID verdictId = finalTemplate("TEMPLATE_READY");
        UUID generationId = UUID.randomUUID();
        UUID jobId = seed.runningJob(verdictId, JobKind.SENTENCE, generationId, VALID_LEASE, PAST_DEADLINE);

        assertRejected(() -> service.begin(verdictId, request(jobId, generationId)),
                HttpStatus.CONFLICT, "STALE_GENERATION");
    }

    @Test
    @DisplayName("10 §4.3 이미 실패 보고한 generation → 409 STALE_GENERATION")
    void failedGenerationRejected() {
        UUID verdictId = pending(BEFORE_DEADLINE);
        UUID generationId = UUID.randomUUID();
        UUID jobId = seed.runningJob(verdictId, JobKind.SENTENCE, generationId, VALID_LEASE, BEFORE_DEADLINE);
        seed.updateVerdict(verdictId, "last_failed_generation_id = ?, last_failed_code = 'VENDOR_UNAVAILABLE'",
                generationId);

        assertRejected(() -> service.begin(verdictId, request(jobId, generationId)),
                HttpStatus.CONFLICT, "STALE_GENERATION");
    }

    @Test
    @DisplayName("10 §4.3 없는 verdict → 404 NOT_FOUND")
    void missingVerdict() {
        assertRejected(() -> service.begin(UUID.randomUUID(), request(UUID.randomUUID(), UUID.randomUUID())),
                HttpStatus.NOT_FOUND, "NOT_FOUND");
    }

    @Test
    @DisplayName("10 §4.7 HTTP 거부 본문은 {\"code\"} 하나(409·404), 성공 본문은 fixed_sentencing null 키 포함")
    void httpBodies() throws Exception {
        UUID verdictId = pending(BEFORE_DEADLINE);
        UUID generationId = UUID.randomUUID();
        UUID jobId = seed.runningJob(verdictId, JobKind.SENTENCE, generationId, VALID_LEASE, BEFORE_DEADLINE);
        String body = "{\"job_id\":\"%s\",\"generation_id\":\"%s\",\"verdict_version\":%d}";

        mockMvc.perform(post("/internal/v1/verdicts/{id}/begin-generation", verdictId)
                        .header("Authorization", "Bearer test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.formatted(jobId, generationId, 9)))
                .andExpect(status().isConflict())
                .andExpect(content().json("{\"code\":\"STALE_GENERATION\"}", JsonCompareMode.STRICT));

        mockMvc.perform(post("/internal/v1/verdicts/{id}/begin-generation", UUID.randomUUID())
                        .header("Authorization", "Bearer test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.formatted(jobId, generationId, 1)))
                .andExpect(status().isNotFound())
                .andExpect(content().json("{\"code\":\"NOT_FOUND\"}", JsonCompareMode.STRICT));

        mockMvc.perform(post("/internal/v1/verdicts/{id}/begin-generation", verdictId)
                        .header("Authorization", "Bearer test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.formatted(jobId, generationId, 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fixed_sentencing").value((Object) null))
                .andExpect(jsonPath("$.text_version").value(0))
                .andExpect(jsonPath("$.deadline_at").isString());
    }
}
