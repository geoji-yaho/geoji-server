package com.ttegeoji.backend.verdict;

import com.ttegeoji.backend.config.InternalApiException;
import com.ttegeoji.backend.privacy.PrivacyEpochRepository;
import com.ttegeoji.backend.privacy.ScopeKeys;
import com.ttegeoji.backend.support.PostgresContainerSupport;
import com.ttegeoji.backend.verdict.FinalizeService.FinalizeResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 서비스가 실제로 커밋해야 재전송·잠금 재확인을 볼 수 있어 테스트 트랜잭션을 두지 않는다. 사건마다 새 id 라 서로 섞이지 않는다.
// 픽스처 JSON 은 FinalizeRequestParserTest 의 텍스트 블록(출처 geoji-agent contracts/fixtures/*-taxi.json)
@SpringBootTest
@AutoConfigureMockMvc
class FinalizeServiceTest extends PostgresContainerSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String POLICY = """
            {"version": "sentencing-band-v1",
             "allowed_sentences": [{"code": "probation", "rank": 1}, {"code": "oneDay", "rank": 2}],
             "fallback_sentence": "oneDay", "reason_required": true}""";
    private static final List<String> LABELS = List.of("F0", "F1", "F2", "F3", "F4", "F5", "F6");

    @Autowired
    private FinalizeService service;
    @Autowired
    private PrivacyEpochRepository privacyEpochs;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MockMvc mockMvc;

    /** 판결 하나와 지금 활성인 job. TEXT_RETRY 를 시작하면 jobId·generationId 가 바뀐다 */
    private static final class Case {
        UUID authorId;
        UUID postId;
        UUID verdictId;
        UUID dossierId;
        List<UUID> roomIds = new ArrayList<>();
        UUID jobId;
        UUID generationId;
        long textVersion;
    }

    // ---- 최초 SENTENCE ----

    @Test
    @DisplayName("10 §5 최초 성공 → FINAL/AI·AI_READY·RETAIN 1·job SUCCEEDED·commit record")
    void firstSuccess() {
        Case c = newCase();

        FinalizeResult result = service.finalizeVerdict(c.verdictId.toString(), bytes(body(c)));

        assertThat(result.verdictId()).isEqualTo(c.verdictId);
        assertThat(result.textVersion()).isEqualTo(1L);
        assertThat(result.committedAt()).isNotNull();
        Map<String, Object> verdict = jdbc.queryForMap("""
                SELECT sentence_status, sentence::text AS sentence, sentence_source, sentencing_reason, reason_source,
                       text_status, text_version, active_job_id, active_generation_id, retry_round, pending_retry_at
                  FROM verdicts WHERE id = ?""", c.verdictId);
        assertThat(verdict).containsEntry("sentence_status", "FINAL").containsEntry("sentence", "oneDay")
                .containsEntry("sentence_source", "AI").containsEntry("reason_source", "AI")
                .containsEntry("text_status", "AI_READY").containsEntry("text_version", 1L)
                .containsEntry("active_job_id", null).containsEntry("active_generation_id", null)
                .containsEntry("retry_round", 0).containsEntry("pending_retry_at", null);
        assertThat((String) verdict.get("sentencing_reason")).startsWith("유죄율 75%");
        assertThat(jdbc.queryForList("SELECT intensity::text || ':' || source || ':' || text_version FROM verdict_texts WHERE verdict_id = ? ORDER BY intensity",
                String.class, c.verdictId)).containsExactly("mild:AI:1", "spicy:AI:1", "hell:AI:1");
        assertThat(retainCount(c)).isEqualTo(1);
        assertThat(jobStatus(c.jobId)).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT text_version FROM ai.verdict_commit_records WHERE generation_id = ?",
                Long.class, c.generationId)).isEqualTo(1L);
    }

    @Test
    @DisplayName("10 §13 작업 3 finalize 응답 유실 후 재전송 → 같은 200·text_version 동일·RETAIN 여전히 1")
    void resendReturnsSameResult() {
        Case c = newCase();
        byte[] body = bytes(body(c));

        FinalizeResult first = service.finalizeVerdict(c.verdictId.toString(), body);
        FinalizeResult second = service.finalizeVerdict(c.verdictId.toString(), body);

        assertThat(second).isEqualTo(first);
        assertThat(second.textVersion()).isEqualTo(1L);
        assertThat(retainCount(c)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT text_version FROM verdicts WHERE id = ?", Long.class, c.verdictId))
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("10 §5 첫 성공 뒤 삭제가 일어나도 같은 본문 재전송은 commit 사실만 같은 200(409 아님)")
    void resendAfterDeletionReturnsCommitFact() {
        Case c = newCase();
        byte[] body = bytes(body(c));
        FinalizeResult first = service.finalizeVerdict(c.verdictId.toString(), body);
        jdbc.update("UPDATE posts SET deleted_at = now() WHERE id = ?", c.postId);
        privacyEpochs.bump(ScopeKeys.post(c.postId));

        assertThat(service.finalizeVerdict(c.verdictId.toString(), body)).isEqualTo(first);
    }

    @Test
    @DisplayName("10 §5 3단계 공유 방 철회(audience 변경) 뒤 → 409 EVIDENCE_INVALIDATED")
    void audienceShrunk() {
        Case c = newCase();
        byte[] body = bytes(body(c));
        jdbc.update("DELETE FROM post_rooms WHERE post_id = ? AND room_id = ?", c.postId, c.roomIds.get(2));

        assertRejected(c, body, HttpStatus.CONFLICT, "EVIDENCE_INVALIDATED");
    }

    @Test
    @DisplayName("10 §5 hash 형식 오류 → 422 INVALID_DRAFT")
    void hashFormat() {
        Case c = newCase();
        ObjectNode body = body(c);
        body.put("draft_hash", "A".repeat(64));

        assertRejected(c, bytes(body), HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_DRAFT");
    }

    @Test
    @DisplayName("10 §5 가짜 백엔드 검사 순서: hash 형식 오류와 다른 generation 이 함께 오면 409 STALE_GENERATION 이 먼저")
    void staleBeforeHashFormat() {
        Case c = newCase();
        ObjectNode body = body(c);
        body.put("draft_hash", "A".repeat(64));
        body.put("generation_id", UUID.randomUUID().toString());

        assertRejected(c, bytes(body), HttpStatus.CONFLICT, "STALE_GENERATION");
    }

    @Test
    @DisplayName("10 §5 같은 generation 다른 본문 → 409 IDEMPOTENCY_CONFLICT")
    void sameGenerationDifferentBody() {
        Case c = newCase();
        service.finalizeVerdict(c.verdictId.toString(), bytes(body(c)));

        ObjectNode other = body(c);
        other.put("prompt_bundle_version", "prompts-other");

        assertRejected(c, bytes(other), HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT");
    }

    @Test
    @DisplayName("10 §13 작업 8 템플릿 이후 늦은 성공 — watchdog 폴백(TEMPLATE_READY) 뒤 이전 generation finalize → 409 STALE_GENERATION")
    void lateSuccessAfterTemplate() {
        Case c = newCase();
        byte[] late = bytes(body(c));
        // watchdog(10 §6)이 한 일을 재현한다
        jdbc.update("""
                UPDATE verdicts SET sentence_status = 'FINAL', sentence = CAST('oneDay' AS sentence), sentence_source = 'RULE',
                       reason_source = 'TEMPLATE', text_status = 'TEMPLATE_READY', active_job_id = NULL,
                       active_generation_id = NULL, retry_round = 1
                 WHERE id = ?""", c.verdictId);
        jdbc.update("UPDATE ai.jobs SET status = 'CANCELLED', owner_id = NULL, generation_id = NULL, lease_until = NULL WHERE id = ?",
                c.jobId);

        assertRejected(c, late, HttpStatus.CONFLICT, "STALE_GENERATION");
        assertThat(retainCount(c)).isZero();
    }

    @Test
    @DisplayName("10 §5 다른 generation 이 활성 → 409 STALE_GENERATION")
    void otherGenerationActive() {
        Case c = newCase();
        ObjectNode body = body(c);
        body.put("generation_id", UUID.randomUUID().toString());

        assertRejected(c, bytes(body), HttpStatus.CONFLICT, "STALE_GENERATION");
    }

    @Test
    @DisplayName("10 §5 최초 노출 마감 지남 → 409 DEADLINE_EXCEEDED")
    void deadlinePassed() {
        Case c = newCase();
        jdbc.update("UPDATE verdicts SET deadline_at = now() - interval '1 second' WHERE id = ?", c.verdictId);

        assertRejected(c, bytes(body(c)), HttpStatus.CONFLICT, "DEADLINE_EXCEEDED");
    }

    @Test
    @DisplayName("10 §5 epoch 증가 뒤 최초 finalize → 409 EVIDENCE_INVALIDATED")
    void epochBumpedBeforeFirstFix() {
        Case c = newCase();
        byte[] body = bytes(body(c));
        privacyEpochs.bump(ScopeKeys.user(c.authorId));

        assertRejected(c, body, HttpStatus.CONFLICT, "EVIDENCE_INVALIDATED");
    }

    @Test
    @DisplayName("10 §5 원본 게시물 삭제 뒤 → 409 EVIDENCE_INVALIDATED")
    void postDeleted() {
        Case c = newCase();
        jdbc.update("UPDATE posts SET deleted_at = now() WHERE id = ?", c.postId);

        assertRejected(c, bytes(body(c)), HttpStatus.CONFLICT, "EVIDENCE_INVALIDATED");
    }

    @Test
    @DisplayName("10 §5 draft_hash 불일치 → 422 INVALID_DRAFT")
    void hashMismatch() {
        Case c = newCase();
        ObjectNode body = body(c);
        body.put("draft_hash", "0".repeat(64));

        assertRejected(c, bytes(body), HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_DRAFT");
    }

    @Test
    @DisplayName("10 §5 evaluation_draft_hash 가 draft_hash 와 다름 → 422 INVALID_DRAFT")
    void evaluationHashMismatch() {
        Case c = newCase();
        ObjectNode body = body(c);
        body.put("evaluation_draft_hash", "0".repeat(64));

        assertRejected(c, bytes(body), HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_DRAFT");
    }

    @Test
    @DisplayName("10 §5 DraftHash 가 계산할 수 없는 문자열(짝 없는 서로게이트) → 500 이 아니라 422 INVALID_DRAFT")
    void unpairedSurrogateIs422() {
        Case c = newCase();
        ObjectNode body = body(c);
        text(body, 0).put("headline", "SURROGATE_MARK");
        byte[] raw = MAPPER.writeValueAsString(body).replace("SURROGATE_MARK", "\\ud800").getBytes(StandardCharsets.UTF_8);

        assertRejected(c, raw, HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_DRAFT");
    }

    @Test
    @DisplayName("10 §5 검수 보고서 pass=false → 422 INVALID_DRAFT")
    void evaluationNotPassed() {
        Case c = newCase();
        ObjectNode body = body(c, draft(), sentencing(), evaluationWith(e -> ((ObjectNode) e.get("texts").get(2)).put("pass", false)));

        assertRejected(c, bytes(body), HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_DRAFT");
    }

    @Test
    @DisplayName("10 §5 검수 보고서에 강도 누락 → 422 INVALID_DRAFT")
    void evaluationMissingIntensity() {
        Case c = newCase();
        ObjectNode body = body(c, draft(), sentencing(), evaluationWith(e -> ((ArrayNode) e.get("texts")).remove(2)));

        assertRejected(c, bytes(body), HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_DRAFT");
    }

    @Test
    @DisplayName("10 §5 허용 목록 밖 형량(life) → 422 INVALID_DRAFT")
    void sentenceOutsideAllowedList() {
        Case c = newCase();
        ObjectNode sentencing = sentencing();
        sentencing.put("sentence", "life");

        assertRejected(c, bytes(body(c, draft(), sentencing, evaluation())), HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_DRAFT");
    }

    @Test
    @DisplayName("10 §5 최초 확정인데 sentencing null → 422 INVALID_DRAFT")
    void firstFixWithoutSentencing() {
        Case c = newCase();

        assertRejected(c, bytes(body(c, draft(), null, evaluation())), HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_DRAFT");
    }

    @Test
    @DisplayName("10 §5 모르는 근거 라벨(F9) → 422 INVALID_DRAFT")
    void unknownEvidenceLabel() {
        Case c = newCase();
        ObjectNode draft = draft();
        ((ArrayNode) draft.get("texts").get(0).get("statement").get(1).get("evidence_labels")).add("F9");

        assertRejected(c, bytes(body(c, draft, sentencing(), evaluation())), HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_DRAFT");
    }

    @Test
    @DisplayName("10 §5 guardrail_policy_version 이 설정값과 다름 → 422 INVALID_DRAFT")
    void guardrailPolicyMismatch() {
        Case c = newCase();
        ObjectNode body = body(c);
        body.put("guardrail_policy_version", "guardrail-v1");

        assertRejected(c, bytes(body), HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_DRAFT");
    }

    @Test
    @DisplayName("10 §5 최초 강도 집합이 target_intensities 와 다름 → 422 INVALID_DRAFT")
    void firstFixMissingIntensity() {
        Case c = newCase();
        ObjectNode draft = draft();
        ((ArrayNode) draft.get("texts")).remove(2);

        assertRejected(c, bytes(body(c, draft, sentencing(), evaluation())), HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_DRAFT");
    }

    @Test
    @DisplayName("10 §5 짤 태그가 배심 결과와 맞지 않음(유죄인데 APPROVED) → 422 INVALID_DRAFT")
    void memeTagContradictsResult() {
        Case c = newCase();
        ObjectNode draft = draft();
        draft.put("meme_tag", "APPROVED");

        assertRejected(c, bytes(body(c, draft, sentencing(), evaluation())), HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_DRAFT");
    }

    @Test
    @DisplayName("10 §5 verdict 없음 → 404 NOT_FOUND")
    void verdictNotFound() {
        Case c = newCase();
        byte[] body = bytes(body(c));

        assertThatThrownBy(() -> service.finalizeVerdict(UUID.randomUUID().toString(), body))
                .isInstanceOfSatisfying(InternalApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(e.getCode()).isEqualTo("NOT_FOUND");
                });
    }

    @Test
    @DisplayName("10 §5 10단계 일부 강도 TEMPLATE → TEMPLATE_READY + retry_round 1·pending_retry_at 5분 뒤")
    void partialTemplate() {
        Case c = newCase();

        service.finalizeVerdict(c.verdictId.toString(), bytes(body(c, draftWithTemplate("spicy"), sentencing(), evaluation())));

        Map<String, Object> verdict = jdbc.queryForMap("""
                SELECT text_status, retry_round, sentence_status,
                       pending_retry_at BETWEEN now() + interval '4 minutes' AND now() + interval '6 minutes' AS in_five
                  FROM verdicts WHERE id = ?""", c.verdictId);
        assertThat(verdict).containsEntry("text_status", "TEMPLATE_READY").containsEntry("retry_round", 1)
                .containsEntry("sentence_status", "FINAL").containsEntry("in_five", true);
        assertThat(retainCount(c)).isEqualTo(1);
    }

    @Test
    @DisplayName("10 §5 9단계 text_evidence_refs 는 문구와 같은 text_version·statement[j]")
    void evidenceRefsShareTextVersion() {
        Case c = newCase();

        service.finalizeVerdict(c.verdictId.toString(), bytes(body(c)));

        assertThat(jdbc.queryForList("SELECT DISTINCT text_version FROM ai.text_evidence_refs WHERE verdict_id = ?",
                Long.class, c.verdictId)).containsExactly(1L);
        // 픽스처 라벨: mild F0 · spicy F0,F2,F3,F1 · hell F0,F1,F1
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai.text_evidence_refs WHERE verdict_id = ?",
                Integer.class, c.verdictId)).isEqualTo(8);
        assertThat(jdbc.queryForList("""
                SELECT r.field_path FROM ai.text_evidence_refs r JOIN ai.evidence e ON e.id = r.evidence_id
                 WHERE r.verdict_id = ? AND r.intensity = 'spicy' AND e.label = 'F2'""", String.class, c.verdictId))
                .containsExactly("statement[1]");
    }

    // ---- TEXT_RETRY ----

    @Test
    @DisplayName("10 §5 TEXT_RETRY 부분 강도 ⊆ 허용 → 받은 강도만 갱신·나머지 행 유지·전체 AI 면 AI_READY")
    void textRetryPartialSubset() {
        Case c = newCase();
        service.finalizeVerdict(c.verdictId.toString(), bytes(body(c, draftWithTemplate("spicy"), sentencing(), evaluation())));
        beginTextRetry(c);

        FinalizeResult result = service.finalizeVerdict(c.verdictId.toString(),
                bytes(body(c, draftOnly("spicy"), sentencing(), evaluationOnly("spicy"))));

        assertThat(result.textVersion()).isEqualTo(2L);
        assertThat(jdbc.queryForList("SELECT intensity::text || ':' || source || ':' || text_version FROM verdict_texts WHERE verdict_id = ? ORDER BY intensity",
                String.class, c.verdictId)).containsExactly("mild:AI:1", "spicy:AI:2", "hell:AI:1");
        Map<String, Object> verdict = jdbc.queryForMap(
                "SELECT text_status, text_version, pending_retry_at, active_job_id FROM verdicts WHERE id = ?", c.verdictId);
        assertThat(verdict).containsEntry("text_status", "AI_READY").containsEntry("text_version", 2L)
                .containsEntry("pending_retry_at", null).containsEntry("active_job_id", null);
        assertThat(retainCount(c)).isEqualTo(1);
        assertThat(jobStatus(c.jobId)).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForList("SELECT DISTINCT intensity FROM ai.text_evidence_refs WHERE verdict_id = ? AND text_version = 2",
                String.class, c.verdictId)).containsExactly("spicy");
    }

    @Test
    @DisplayName("10 §5 TEXT_RETRY sentencing null 도 받는다(형량은 DB 고정값)")
    void textRetryWithoutSentencing() {
        Case c = newCase();
        service.finalizeVerdict(c.verdictId.toString(), bytes(body(c, draftWithTemplate("hell"), sentencing(), evaluation())));
        beginTextRetry(c);

        FinalizeResult result = service.finalizeVerdict(c.verdictId.toString(),
                bytes(body(c, draftOnly("hell"), null, evaluationOnly("hell"))));

        assertThat(result.textVersion()).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT sentence::text FROM verdicts WHERE id = ?", String.class, c.verdictId))
                .isEqualTo("oneDay");
    }

    @Test
    @DisplayName("10 §5 TEXT_RETRY 중복 강도 → 422 INVALID_DRAFT")
    void textRetryDuplicateIntensity() {
        Case c = newCase();
        service.finalizeVerdict(c.verdictId.toString(), bytes(body(c, draftWithTemplate("spicy"), sentencing(), evaluation())));
        beginTextRetry(c);
        ObjectNode draft = draftOnly("spicy");
        ((ArrayNode) draft.get("texts")).add(draft.get("texts").get(0).deepCopy());

        assertRejected(c, bytes(body(c, draft, sentencing(), evaluationOnly("spicy"))),
                HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_DRAFT");
    }

    @Test
    @DisplayName("10 §5 TEXT_RETRY 에서 형량·양형 이유 변경 → 422 INVALID_DRAFT")
    void textRetryCannotChangeSentencing() {
        Case c = newCase();
        service.finalizeVerdict(c.verdictId.toString(), bytes(body(c, draftWithTemplate("spicy"), sentencing(), evaluation())));
        beginTextRetry(c);

        ObjectNode otherSentence = sentencing();
        otherSentence.put("sentence", "probation");
        assertRejected(c, bytes(body(c, draftOnly("spicy"), otherSentence, evaluationOnly("spicy"))),
                HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_DRAFT");

        ObjectNode otherReason = sentencing();
        otherReason.put("sentencing_reason", "다른 이유");
        assertRejected(c, bytes(body(c, draftOnly("spicy"), otherReason, evaluationOnly("spicy"))),
                HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_DRAFT");
    }

    @Test
    @DisplayName("10 §13 작업 8 retry 중 삭제 — epoch 증가 뒤 TEXT_RETRY finalize → 409 EVIDENCE_INVALIDATED")
    void deletionDuringRetry() {
        Case c = newCase();
        service.finalizeVerdict(c.verdictId.toString(), bytes(body(c, draftWithTemplate("spicy"), sentencing(), evaluation())));
        beginTextRetry(c);
        byte[] retry = bytes(body(c, draftOnly("spicy"), sentencing(), evaluationOnly("spicy")));
        privacyEpochs.bump(ScopeKeys.post(c.postId));

        assertRejected(c, retry, HttpStatus.CONFLICT, "EVIDENCE_INVALIDATED");
        assertThat(jdbc.queryForObject("SELECT text_status FROM verdicts WHERE id = ?", String.class, c.verdictId))
                .isEqualTo("TEMPLATE_READY");
    }

    @Test
    @DisplayName("10 §5 TEXT_RETRY job 제한 시각 지남 → 409 DEADLINE_EXCEEDED")
    void textRetryJobDeadline() {
        Case c = newCase();
        service.finalizeVerdict(c.verdictId.toString(), bytes(body(c, draftWithTemplate("spicy"), sentencing(), evaluation())));
        beginTextRetry(c);
        jdbc.update("UPDATE ai.jobs SET deadline_at = now() - interval '1 second' WHERE id = ?", c.jobId);

        assertRejected(c, bytes(body(c, draftOnly("spicy"), sentencing(), evaluationOnly("spicy"))),
                HttpStatus.CONFLICT, "DEADLINE_EXCEEDED");
    }

    @Test
    @DisplayName("10 §11 짤 meme_image_id 는 최초에 고정되고 재생성해도 바뀌지 않는다")
    void memeFixedAcrossRegeneration() {
        insertMeme("GUILTY_LIGHT", "{}", "{}", "{}");
        Case c = newCase();
        service.finalizeVerdict(c.verdictId.toString(), bytes(body(c, draftWithTemplate("spicy"), sentencing(), evaluation())));
        UUID fixed = jdbc.queryForObject("SELECT meme_image_id FROM verdicts WHERE id = ?", UUID.class, c.verdictId);
        assertThat(fixed).isNotNull();

        // 점수가 가장 높은 후보가 새로 생겨도 유지
        insertMeme("GUILTY_LIGHT", "{REPEAT_OFFENSE,CHEAPER_ALTERNATIVE}", "{DISAPPROVAL}", "{택시,늦잠,알람}");
        beginTextRetry(c);
        service.finalizeVerdict(c.verdictId.toString(), bytes(body(c, draftOnly("spicy"), sentencing(), evaluationOnly("spicy"))));

        assertThat(jdbc.queryForObject("SELECT meme_image_id FROM verdicts WHERE id = ?", UUID.class, c.verdictId))
                .isEqualTo(fixed);
    }

    @Test
    @DisplayName("10 §4.7 내부 API 로 finalize → 200 {verdict_id, text_version, committed_at}, 같은 바이트 재전송은 같은 응답 본문")
    void httpEndpoint() throws Exception {
        Case c = newCase();
        byte[] body = bytes(body(c));

        String first = mockMvc.perform(workerPost(c, body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verdict_id").value(c.verdictId.toString()))
                .andExpect(jsonPath("$.text_version").value(1))
                .andExpect(jsonPath("$.committed_at").isString())
                .andReturn().getResponse().getContentAsString();
        String resent = mockMvc.perform(workerPost(c, body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(resent).isEqualTo(first);

        mockMvc.perform(post("/internal/v1/verdicts/{id}/finalize", c.verdictId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().json("{\"code\":\"INVALID_DRAFT\"}"));
    }

    // ---- 픽스처 ----

    /** 워커 → 백엔드 헤더 5종(10 §4.7). X-Request-Id 는 시도마다 새 값 */
    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder workerPost(Case c, byte[] body) {
        return post("/internal/v1/verdicts/{id}/finalize", c.verdictId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .header("X-Trace-Id", "trace")
                .header("X-Request-Id", UUID.randomUUID().toString())
                .header("X-Job-Id", c.jobId.toString())
                .header("X-Generation-Id", c.generationId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private Case newCase() {
        Case c = new Case();
        c.authorId = UUID.randomUUID();
        jdbc.update("INSERT INTO profiles (id, nickname, monthly_budget) VALUES (?, 'finalize-test', 300000)", c.authorId);
        for (String level : List.of("mild", "spicy", "hell")) {
            c.roomIds.add(jdbc.queryForObject("""
                    INSERT INTO rooms (name, spice_level, vote_deadline_minutes, created_by)
                    VALUES (?, CAST(? AS spice_level), 30, ?) RETURNING id""", UUID.class, "room-" + level, level, c.authorId));
        }
        c.postId = jdbc.queryForObject("""
                INSERT INTO posts (author_id, post_type, amount_krw, category, item, reason, intake_status, intake_source,
                                   vote_deadline_at)
                VALUES (?, CAST('spent' AS post_type), 12000, '교통/택시', '택시', '늦잠 자서 택시 탐', 'PASS', 'AI', now())
                RETURNING id""", UUID.class, c.authorId);
        for (UUID room : c.roomIds) {
            jdbc.update("INSERT INTO post_rooms (post_id, room_id) VALUES (?, ?)", c.postId, room);
        }
        c.verdictId = jdbc.queryForObject("""
                INSERT INTO verdicts (post_id, jury_result, policy_snapshot, confirmed_at, deadline_at, target_intensities,
                                      default_intensity)
                VALUES (?, CAST('guilty' AS verdict), CAST(? AS jsonb), now(), now() + interval '10 seconds',
                        CAST('["mild","spicy","hell"]' AS jsonb), CAST('mild' AS spice_level))
                RETURNING id""", UUID.class, c.postId, POLICY);
        c.generationId = UUID.randomUUID();
        c.jobId = insertRunningJob("SENTENCE", c.generationId, "now() + interval '10 seconds'");
        jdbc.update("UPDATE verdicts SET active_job_id = ?, active_generation_id = ? WHERE id = ?",
                c.jobId, c.generationId, c.verdictId);

        c.dossierId = UUID.randomUUID();
        Map<String, String> labelMap = new LinkedHashMap<>();
        for (String label : LABELS) {
            labelMap.put(label, UUID.randomUUID().toString());
        }
        jdbc.update("""
                INSERT INTO ai.dossiers (id, post_id, snapshot_hash, label_map, privacy_versions)
                VALUES (?, ?, 'hash', CAST(? AS jsonb), '[]'::jsonb)""",
                c.dossierId, c.postId.toString(), MAPPER.writeValueAsString(labelMap));
        labelMap.forEach((label, evidenceId) -> jdbc.update("""
                INSERT INTO ai.evidence (id, dossier_id, label, epistemic_type, fact_type, text, scope)
                VALUES (?, ?, ?, 'DB_RECORD', 'SPEND', 'fact', '{}'::jsonb)""",
                UUID.fromString(evidenceId), c.dossierId, label));
        return c;
    }

    /** begin-generation 이 TEXT_RETRY 에 한 일을 재현한다: 새 job·generation 을 active 로 */
    private void beginTextRetry(Case c) {
        c.textVersion = jdbc.queryForObject("SELECT text_version FROM verdicts WHERE id = ?", Long.class, c.verdictId);
        c.generationId = UUID.randomUUID();
        c.jobId = insertRunningJob("TEXT_RETRY", c.generationId, "now() + interval '20 seconds'");
        jdbc.update("UPDATE verdicts SET active_job_id = ?, active_generation_id = ? WHERE id = ?",
                c.jobId, c.generationId, c.verdictId);
    }

    private UUID insertRunningJob(String kind, UUID generationId, String deadlineExpr) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO ai.jobs (id, event_id, event_type, kind, dedupe_key, aggregate_id, aggregate_version, payload,
                                     status, priority, attempts, max_attempts, deadline_at, lease_until, owner_id,
                                     generation_id, trace_id)
                VALUES (?, gen_random_uuid(), 'test.event', ?, ?, 'agg', 1, '{}'::jsonb, 'RUNNING', 100, 1, 2, %s,
                        now() + interval '1 minute', 'worker-1', ?, 'trace')""".formatted(deadlineExpr),
                id, kind, "finalize-test:" + id, generationId);
        return id;
    }

    private void insertMeme(String tag, String strategies, String emotions, String keywords) {
        jdbc.update("""
                INSERT INTO meme_images (tag, strategies, emotions, keywords, image_url)
                VALUES (?, CAST(? AS text[]), CAST(? AS text[]), CAST(? AS text[]), 'https://cdn.example/meme.png')""",
                tag, strategies, emotions, keywords);
    }

    private ObjectNode body(Case c) {
        return body(c, draft(), sentencing(), evaluation());
    }

    /** draft_hash·evaluation_draft_hash 는 넘긴 draft·sentencing 으로 계산한다. privacy_versions 는 지금 epoch */
    private ObjectNode body(Case c, ObjectNode draft, ObjectNode sentencingOrNull, ObjectNode evaluation) {
        String hash = DraftHash.sha256Hex(draft, sentencingOrNull);
        ObjectNode body = MAPPER.createObjectNode();
        body.put("schema_version", 1);
        body.put("job_id", c.jobId.toString());
        body.put("generation_id", c.generationId.toString());
        body.put("verdict_version", 1);
        body.put("expected_text_version", c.textVersion);
        body.put("dossier_id", c.dossierId.toString());
        ArrayNode privacy = body.putArray("privacy_versions");
        List<String> keys = new ArrayList<>(List.of(ScopeKeys.post(c.postId), ScopeKeys.user(c.authorId)));
        c.roomIds.forEach(room -> keys.add(ScopeKeys.room(room)));
        privacyEpochs.read(keys).forEach((key, epoch) -> {
            ObjectNode item = privacy.addObject();
            item.put("scope_key", key);
            item.put("epoch", epoch);
        });
        body.put("draft_hash", hash);
        if (sentencingOrNull == null) {
            body.putNull("sentencing");
        } else {
            body.set("sentencing", sentencingOrNull);
        }
        body.set("draft", draft);
        body.set("evaluation", evaluation);
        body.put("evaluation_draft_hash", hash);
        body.put("prompt_bundle_version", "prompts-test");
        body.put("guardrail_policy_version", "guardrail-v2");
        ObjectNode models = body.putObject("model_ids");
        models.put("sentencing", "model-a");
        models.put("writer", "model-b");
        models.put("evaluator", "model-a");
        return body;
    }

    private static ObjectNode draft() {
        return (ObjectNode) MAPPER.readTree(FinalizeRequestParserTest.WRITER_DRAFT);
    }

    private static ObjectNode draftWithTemplate(String intensity) {
        ObjectNode draft = draft();
        for (JsonNode text : draft.get("texts")) {
            if (intensity.equals(text.get("intensity").stringValue())) {
                ((ObjectNode) text).put("source", "TEMPLATE");
            }
        }
        return draft;
    }

    private static ObjectNode draftOnly(String intensity) {
        ObjectNode draft = draft();
        ArrayNode texts = (ArrayNode) draft.get("texts");
        for (int i = texts.size() - 1; i >= 0; i--) {
            if (!intensity.equals(texts.get(i).get("intensity").stringValue())) {
                texts.remove(i);
            }
        }
        return draft;
    }

    private static ObjectNode sentencing() {
        return (ObjectNode) MAPPER.readTree(FinalizeRequestParserTest.SENTENCING);
    }

    private static ObjectNode evaluation() {
        return (ObjectNode) MAPPER.readTree(FinalizeRequestParserTest.EVALUATION_PASS);
    }

    private static ObjectNode evaluationWith(java.util.function.Consumer<ObjectNode> mutation) {
        ObjectNode evaluation = evaluation();
        mutation.accept(evaluation);
        return evaluation;
    }

    private static ObjectNode evaluationOnly(String intensity) {
        return evaluationWith(e -> {
            ArrayNode texts = (ArrayNode) e.get("texts");
            for (int i = texts.size() - 1; i >= 0; i--) {
                if (!intensity.equals(texts.get(i).get("intensity").stringValue())) {
                    texts.remove(i);
                }
            }
        });
    }

    private static ObjectNode text(ObjectNode body, int index) {
        return (ObjectNode) body.get("draft").get("texts").get(index);
    }

    private static byte[] bytes(JsonNode node) {
        return MAPPER.writeValueAsBytes(node);
    }

    private void assertRejected(Case c, byte[] body, HttpStatus status, String code) {
        assertThatThrownBy(() -> service.finalizeVerdict(c.verdictId.toString(), body))
                .isInstanceOfSatisfying(InternalApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(status);
                    assertThat(e.getCode()).isEqualTo(code);
                });
    }

    private int retainCount(Case c) {
        return jdbc.queryForObject("SELECT count(*) FROM ai.jobs WHERE kind = 'RETAIN' AND aggregate_id = ?",
                Integer.class, c.verdictId.toString());
    }

    private String jobStatus(UUID jobId) {
        return jdbc.queryForObject("SELECT status FROM ai.jobs WHERE id = ?", String.class, jobId);
    }
}
