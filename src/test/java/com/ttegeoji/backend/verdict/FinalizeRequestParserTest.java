package com.ttegeoji.backend.verdict;

import com.ttegeoji.backend.config.InternalApiException;
import com.ttegeoji.backend.verdict.FinalizeRequestParser.FinalizeRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// DB 없이 스키마 검증만 본다. 정본 geoji-agent contracts/finalize-v1.schema.json
class FinalizeRequestParserTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // 출처: geoji-agent contracts/fixtures/writer-draft-taxi.json (src/test/resources 는 이 작업 소유가 아니라 텍스트 블록으로 둔다)
    static final String WRITER_DRAFT = """
            {
              "schema_version": 1,
              "texts": [
                {
                  "intensity": "mild",
                  "headline": "택시 12,000원, 유죄",
                  "statement": [
                    {"text": "지하철 기본요금 1,400원 기준으로 여덟 번 탈 돈입니다.", "kind": "fact", "evidence_labels": ["F0"]},
                    {"text": "다음엔 알람을 조금만 일찍 맞춰 봐요.", "kind": "opinion", "evidence_labels": []}
                  ],
                  "banter_strategy": "CHEAPER_ALTERNATIVE",
                  "selected_candidate_id": null,
                  "attack_angle": "CONVERSION",
                  "source": "AI"
                },
                {
                  "intensity": "spicy",
                  "headline": "또 늦잠, 또 택시",
                  "statement": [
                    {"text": "택시 12,000원은 지하철 여덟 번 값입니다.", "kind": "fact", "evidence_labels": ["F0"]},
                    {"text": "지난주에도 늦잠, 이번 주도 늦잠. 택시가 아니라 이불이 문제입니다.", "kind": "claim", "evidence_labels": ["F2", "F3"]},
                    {"text": "최근 7일 동안 택시 3회, 합계 31,000원.", "kind": "fact", "evidence_labels": ["F1"]}
                  ],
                  "banter_strategy": "REPEAT_OFFENSE",
                  "selected_candidate_id": "8d3f1a62-6c95-4b07-8e41-9a2d5f3b7c18",
                  "attack_angle": "CONVERSION",
                  "source": "AI"
                },
                {
                  "intensity": "hell",
                  "headline": "알람 시계 세 개 값",
                  "statement": [
                    {"text": "12,000원이면 지하철 여덟 번입니다. 여덟 번.", "kind": "fact", "evidence_labels": ["F0"]},
                    {"text": "최근 7일 동안 택시 3회, 31,000원을 태웠습니다.", "kind": "fact", "evidence_labels": ["F1"]},
                    {"text": "31,000원이면 알람 시계 세 개입니다. 하나만 사세요.", "kind": "claim", "evidence_labels": ["F1"]}
                  ],
                  "banter_strategy": "CHEAPER_ALTERNATIVE",
                  "selected_candidate_id": "6a5d2f83-4b70-4e19-8c26-0f7a9b3e5d14",
                  "attack_angle": "CONVERSION",
                  "source": "AI"
                }
              ],
              "meme_tag": "GUILTY_LIGHT",
              "meme_hints": {"emotion": "DISAPPROVAL", "keywords": ["택시", "늦잠", "알람"]}
            }
            """;

    // 출처: geoji-agent contracts/fixtures/sentencing-taxi.json
    static final String SENTENCING = """
            {
              "schema_version": 1,
              "sentence": "oneDay",
              "sentencing_reason": "유죄율 75%로 실형 범위이나, 이번 달 예산 소진율이 아직 41%라 무기징역까지는 가지 않는다.",
              "reason_source": "AI",
              "evidence_labels": ["F1", "F5"],
              "aggravating": [],
              "mitigating": []
            }
            """;

    // 출처: geoji-agent contracts/fixtures/evaluation-taxi-pass.json
    static final String EVALUATION_PASS = """
            {
              "schema_version": 1,
              "policy_version": "guardrail-v2",
              "sentence_check": {"pass": true, "violations": []},
              "sentencing_reason_check": {"pass": true, "violations": []},
              "texts": [
                {"intensity": "mild", "pass": true, "violations": [], "problem_sentences": []},
                {"intensity": "spicy", "pass": true, "violations": [], "problem_sentences": []},
                {"intensity": "hell", "pass": true, "violations": [], "problem_sentences": []}
              ]
            }
            """;

    @Test
    @DisplayName("10 §5 픽스처로 만든 요청은 통과하고 필드가 채워진다")
    void validRequestParses() {
        FinalizeRequest request = FinalizeRequestParser.parse(bytes(validBody()));

        assertThat(request.verdictVersion()).isEqualTo(1);
        assertThat(request.sentencing().sentence()).isEqualTo("oneDay");
        assertThat(request.sentencing().reasonSource()).isEqualTo("AI");
        assertThat(request.draft().texts()).hasSize(3);
        assertThat(request.draft().memeEmotion()).isEqualTo("DISAPPROVAL");
        assertThat(request.draft().texts().get(1).statement().get(1).evidenceLabels()).containsExactly("F2", "F3");
        assertThat(request.evaluation().texts()).allMatch(FinalizeRequestParser.TextEvaluation::pass);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4})
    @DisplayName("10 §5 새 카드 본문 1개와 기존 판결문 2~4개를 모두 허용한다")
    void acceptsCardAndHistoricalStatementCounts(int count) {
        ObjectNode body = validBody();
        for (JsonNode item : body.get("draft").get("texts")) {
            ArrayNode statements = (ArrayNode) item.get("statement");
            JsonNode statement = statements.get(0).deepCopy();
            statements.removeAll();
            for (int i = 0; i < count; i++) {
                statements.add(statement.deepCopy());
            }
        }

        FinalizeRequest request = FinalizeRequestParser.parse(bytes(body));
        assertThat(request.draft().texts()).allSatisfy(text -> assertThat(text.statement()).hasSize(count));
        assertThat(request.draft().memeEmotion()).isEqualTo("DISAPPROVAL");
        assertThat(request.draft().texts().getFirst().statement().getFirst().evidenceLabels())
                .containsExactly("F0");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 5})
    @DisplayName("10 §5 본문이 없거나 5개 이상이면 422 INVALID_DRAFT")
    void rejectsStatementCountOutsideContract(int count) {
        assertInvalid(body -> {
            ArrayNode statements = (ArrayNode) text(body, 0).get("statement");
            JsonNode statement = statements.get(0).deepCopy();
            statements.removeAll();
            for (int i = 0; i < count; i++) {
                statements.add(statement.deepCopy());
            }
        });
    }

    @Test
    @DisplayName("10 §5 최상위 알 수 없는 필드 → 422 INVALID_DRAFT")
    void unknownTopLevelField() {
        assertInvalid(body -> body.put("verdict", "guilty"));
    }

    @Test
    @DisplayName("10 §5 texts[] 안 알 수 없는 필드 → 422 INVALID_DRAFT")
    void unknownNestedField() {
        assertInvalid(body -> text(body, 0).put("sentence_source", "AI"));
    }

    @Test
    @DisplayName("10 §5 sentencing null 은 허용")
    void sentencingNullAllowed() {
        ObjectNode body = validBody();
        body.putNull("sentencing");

        assertThat(FinalizeRequestParser.parse(bytes(body)).sentencing()).isNull();
    }

    @Test
    @DisplayName("10 §5 meme_hints.emotion 6종 밖 → 422 INVALID_DRAFT")
    void emotionOutsideEnum() {
        assertInvalid(body -> ((ObjectNode) body.get("draft").get("meme_hints")).put("emotion", "HAPPY"));
    }

    @Test
    @DisplayName("10 §5 meme_hints null 은 허용")
    void memeHintsNullAllowed() {
        ObjectNode body = validBody();
        ((ObjectNode) body.get("draft")).putNull("meme_hints");

        assertThat(FinalizeRequestParser.parse(bytes(body)).draft().memeEmotion()).isNull();
    }

    @Test
    @DisplayName("10 §5 9/11 texts[].source 누락 → 422 INVALID_DRAFT")
    void textSourceMissing() {
        assertInvalid(body -> text(body, 0).remove("source"));
    }

    @Test
    @DisplayName("10 §5 9/11 sentencing.reason_source 누락 → 422 INVALID_DRAFT")
    void reasonSourceMissing() {
        assertInvalid(body -> ((ObjectNode) body.get("sentencing")).remove("reason_source"));
    }

    @Test
    @DisplayName("10 §5 필수 필드 누락(draft_hash) → 422 INVALID_DRAFT")
    void requiredFieldMissing() {
        assertInvalid(body -> body.remove("draft_hash"));
    }

    @Test
    @DisplayName("10 §5 9/11 길이·배열 상한: statement 301자·라벨 패턴·keywords 11개·headline 31자 → 422")
    void lengthAndArrayLimits() {
        assertInvalid(body -> ((ObjectNode) text(body, 0).get("statement").get(0)).put("text", "가".repeat(301)));
        assertInvalid(body -> ((ArrayNode) text(body, 0).get("statement").get(0).get("evidence_labels")).add("X1"));
        assertInvalid(body -> {
            ArrayNode keywords = (ArrayNode) body.get("draft").get("meme_hints").get("keywords");
            for (int i = 0; i < 8; i++) {
                keywords.add("k" + i);
            }
        });
        assertInvalid(body -> text(body, 0).put("headline", "가".repeat(31)));
    }

    @Test
    @DisplayName("10 §5 headline 30자는 코드포인트로 센다(보조 평면 문자 30개 통과)")
    void headlineCountsCodePoints() {
        ObjectNode body = validBody();
        text(body, 0).put("headline", "😀".repeat(30));

        assertThat(FinalizeRequestParser.parse(bytes(body)).draft().texts().getFirst().headline()).hasSize(60);
    }

    @Test
    @DisplayName("10 §5 draft_hash 는 스키마 단계에서 문자열만 본다(형식은 가짜 백엔드 순서대로 서비스 7단계), 문자열 아니면 422")
    void hashIsStringAtSchemaStage() {
        ObjectNode body = validBody();
        body.put("draft_hash", "A".repeat(64));
        assertThat(FinalizeRequestParser.parse(bytes(body)).draftHash()).isEqualTo("A".repeat(64));

        assertInvalid(b -> b.put("draft_hash", 42));
    }

    @Test
    @DisplayName("10 §5 JSON 이 아니거나 generation_id 가 UUID 가 아니면 422")
    void notJsonOrBadGeneration() {
        assertThatInvalid(() -> FinalizeRequestParser.peekGenerationId("{".getBytes(StandardCharsets.UTF_8)));
        assertThatInvalid(() -> FinalizeRequestParser.peekGenerationId(new byte[0]));
        assertThatInvalid(() -> FinalizeRequestParser.peekGenerationId(
                "{\"generation_id\": \"1-1-1-1-1\"}".getBytes(StandardCharsets.UTF_8)));
        UUID generation = UUID.randomUUID();
        assertThat(FinalizeRequestParser.peekGenerationId(
                ("{\"generation_id\": \"" + generation + "\"}").getBytes(StandardCharsets.UTF_8))).isEqualTo(generation);
    }

    private static void assertInvalid(Consumer<ObjectNode> mutation) {
        ObjectNode body = validBody();
        mutation.accept(body);
        assertThatInvalid(() -> FinalizeRequestParser.parse(bytes(body)));
    }

    private static void assertThatInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(InternalApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
                    assertThat(e.getCode()).isEqualTo("INVALID_DRAFT");
                });
    }

    private static ObjectNode text(ObjectNode body, int index) {
        return (ObjectNode) body.get("draft").get("texts").get(index);
    }

    private static byte[] bytes(JsonNode node) {
        return MAPPER.writeValueAsBytes(node);
    }

    static ObjectNode validBody() {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("schema_version", 1);
        body.put("job_id", UUID.randomUUID().toString());
        body.put("generation_id", UUID.randomUUID().toString());
        body.put("verdict_version", 1);
        body.put("expected_text_version", 0);
        body.put("dossier_id", UUID.randomUUID().toString());
        ObjectNode privacy = body.putArray("privacy_versions").addObject();
        privacy.put("scope_key", "post:" + UUID.randomUUID());
        privacy.put("epoch", 0);
        body.put("draft_hash", "a".repeat(64));
        body.set("sentencing", MAPPER.readTree(SENTENCING));
        body.set("draft", MAPPER.readTree(WRITER_DRAFT));
        body.set("evaluation", MAPPER.readTree(EVALUATION_PASS));
        body.put("evaluation_draft_hash", "a".repeat(64));
        body.put("prompt_bundle_version", "prompts-test");
        body.put("guardrail_policy_version", "guardrail-v2");
        ObjectNode models = body.putObject("model_ids");
        models.put("sentencing", "model-a");
        models.put("writer", "model-b");
        models.put("evaluator", "model-a");
        return body;
    }
}
