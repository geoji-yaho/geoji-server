package com.ttegeoji.backend.verdict;

import com.ttegeoji.backend.config.InternalApiException;
import org.springframework.http.HttpStatus;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 10 §5 FinalizeRequest 파싱·스키마 검증. 정본은 AI 저장소 contracts/finalize-v1.schema.json 이다.
 * 알 수 없는 필드·필수 누락·enum 밖·길이/개수 상한 초과는 모두 422 INVALID_DRAFT(9/14 결정, 422 코드는 하나).
 * 길이는 Python len 과 같게 코드포인트로 센다. hash 재계산용으로 draft·sentencing 원본 노드를 같이 돌려준다.
 */
public final class FinalizeRequestParser {

    static final String INVALID_DRAFT = "INVALID_DRAFT";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    static final Pattern SHA256_HEX =Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern LABEL = Pattern.compile("^F[0-9]+$");

    private static final Set<String> GUARDRAIL_VERSIONS = Set.of("guardrail-v1", "guardrail-v2");
    private static final Set<String> SOURCES = Set.of("AI", "TEMPLATE");
    private static final Set<String> INTENSITIES = Set.of("mild", "spicy", "hell");
    private static final Set<String> STATEMENT_KINDS = Set.of("fact", "claim", "opinion");
    private static final Set<String> MEME_TAGS =
            Set.of("GUILTY_HEAVY", "GUILTY_LIGHT", "NOT_GUILTY", "APPROVED", "REJECTED");
    private static final Set<String> BANTER_STRATEGIES = Set.of("CHEAPER_ALTERNATIVE", "FREE_ALTERNATIVE",
            "DIY_REPLACEMENT", "PREMISE_REJECTION", "EXCUSE_STRIPPING", "NECESSITY_APPROVAL", "REPEAT_OFFENSE",
            "ROOM_RULE_CALLBACK");
    private static final Set<String> ATTACK_ANGLES = Set.of("CONVERSION", "REPETITION", "EXCUSE_DISSECTION",
            "FUTURE_PROPHECY", "RULE_PERSONIFICATION", "ALTERNATIVE_MOCKERY");
    private static final Set<String> VIOLATION_CODES = Set.of("PERSONAL_ATTACK", "IDENTITY_DEGRADATION",
            "SELF_HARM_LEXICON", "UNGROUNDED_CLAIM", "VERDICT_CONTRADICTION", "INJECTION_FOLLOWED", "UNSAFE_CONTENT",
            "INTENSITY_MISMATCH", "PROFANITY_OUT_OF_LIST", "SENTENCE_REASON_MISMATCH", "SCHEMA_INVALID");

    public record PrivacyVersion(String scopeKey, long epoch) {
    }

    public record Sentencing(String sentence, String sentencingReason, String reasonSource,
                             List<String> evidenceLabels) {
    }

    public record Statement(String text, String kind, List<String> evidenceLabels) {
    }

    /** statementJson 은 verdict_texts.statement 에 그대로 넣는 배열 JSON */
    public record TextDraft(String intensity, String headline, List<Statement> statement, String statementJson,
                            String banterStrategy, String source) {
    }

    /** memeEmotion·memeKeywords 는 meme_hints 가 null 이면 null·빈 목록 */
    public record Draft(List<TextDraft> texts, String memeTag, String memeEmotion, List<String> memeKeywords) {
    }

    public record TextEvaluation(String intensity, boolean pass) {
    }

    public record Evaluation(String policyVersion, boolean sentenceCheckPass, boolean sentencingReasonCheckPass,
                             List<TextEvaluation> texts) {
    }

    public record FinalizeRequest(String jobId, String generationId, int verdictVersion, long expectedTextVersion,
                                  String dossierId, List<PrivacyVersion> privacyVersions, String draftHash,
                                  Sentencing sentencing, JsonNode sentencingNode, Draft draft, JsonNode draftNode,
                                  Evaluation evaluation, String evaluationDraftHash, String promptBundleVersion,
                                  String guardrailPolicyVersion) {
    }

    private FinalizeRequestParser() {
    }

    /**
     * 10 §5 1단계용. 스키마 검증 전에 commit record 를 찾으려고 generation_id 만 꺼낸다.
     * JSON 이 아니거나 generation_id 가 UUID 문자열이 아니면 commit record 가 있을 수 없으므로 422.
     */
    public static UUID peekGenerationId(byte[] body) {
        JsonNode generationId = readTree(body).get("generation_id");
        if (generationId == null || !generationId.isString()) {
            throw invalid();
        }
        return parseUuid(generationId.stringValue());
    }

    public static FinalizeRequest parse(byte[] body) {
        JsonNode root = readTree(body);
        object(root, Set.of("schema_version", "job_id", "generation_id", "verdict_version", "expected_text_version",
                "dossier_id", "privacy_versions", "draft_hash", "sentencing", "draft", "evaluation",
                "evaluation_draft_hash", "prompt_bundle_version", "guardrail_policy_version", "model_ids"), Set.of());

        constOne(root.get("schema_version"));
        JsonNode modelIds = root.get("model_ids");
        object(modelIds, Set.of("sentencing", "writer", "evaluator"), Set.of());
        for (Map.Entry<String, JsonNode> entry : modelIds.properties()) {
            string(entry.getValue());
        }

        List<PrivacyVersion> privacyVersions = new ArrayList<>();
        for (JsonNode item : array(root.get("privacy_versions"), 0, 100)) {
            object(item, Set.of("scope_key", "epoch"), Set.of());
            privacyVersions.add(new PrivacyVersion(string(item.get("scope_key")), integer(item.get("epoch"), 0)));
        }

        JsonNode sentencingNode = root.get("sentencing");
        Sentencing sentencing = sentencingNode.isNull() ? null : sentencing(sentencingNode);
        JsonNode draftNode = root.get("draft");

        return new FinalizeRequest(
                string(root.get("job_id")),
                string(root.get("generation_id")),
                Math.toIntExact(integer(root.get("verdict_version"), 1)),
                integer(root.get("expected_text_version"), 0),
                string(root.get("dossier_id")),
                List.copyOf(privacyVersions),
                // hash 형식은 가짜 백엔드 검사 순서대로 서비스 7단계(마감 뒤)에서 본다(10 §5)
                string(root.get("draft_hash")),
                sentencing,
                sentencingNode,
                draft(draftNode),
                draftNode,
                evaluation(root.get("evaluation")),
                string(root.get("evaluation_draft_hash")),
                string(root.get("prompt_bundle_version")),
                oneOf(root.get("guardrail_policy_version"), GUARDRAIL_VERSIONS));
    }

    private static Sentencing sentencing(JsonNode node) {
        object(node, Set.of("schema_version", "sentence", "sentencing_reason", "reason_source", "evidence_labels",
                "aggravating", "mitigating"), Set.of());
        constOne(node.get("schema_version"));
        JsonNode reason = node.get("sentencing_reason");
        String sentencingReason = reason.isNull() ? null : string(reason, 0, 100);
        for (JsonNode item : array(node.get("aggravating"), 0, 20)) {
            string(item, 0, 100);
        }
        for (JsonNode item : array(node.get("mitigating"), 0, 20)) {
            string(item, 0, 100);
        }
        return new Sentencing(string(node.get("sentence")), sentencingReason,
                oneOf(node.get("reason_source"), SOURCES), labels(node.get("evidence_labels")));
    }

    private static Draft draft(JsonNode node) {
        object(node, Set.of("schema_version", "texts", "meme_tag", "meme_hints"), Set.of());
        constOne(node.get("schema_version"));
        List<TextDraft> texts = new ArrayList<>();
        for (JsonNode text : array(node.get("texts"), 1, 3)) {
            texts.add(textDraft(text));
        }
        String emotion = null;
        List<String> keywords = List.of();
        JsonNode hints = node.get("meme_hints");
        if (!hints.isNull()) {
            object(hints, Set.of("emotion", "keywords"), Set.of());
            emotion = oneOf(hints.get("emotion"), MemeScorer.EMOTIONS);
            List<String> words = new ArrayList<>();
            for (JsonNode keyword : array(hints.get("keywords"), 0, 10)) {
                words.add(string(keyword, 0, 30));
            }
            keywords = List.copyOf(words);
        }
        return new Draft(List.copyOf(texts), oneOf(node.get("meme_tag"), MEME_TAGS), emotion, keywords);
    }

    private static TextDraft textDraft(JsonNode node) {
        object(node, Set.of("intensity", "headline", "statement", "banter_strategy", "selected_candidate_id",
                "attack_angle", "source"), Set.of());
        List<Statement> statements = new ArrayList<>();
        JsonNode statementNode = node.get("statement");
        for (JsonNode item : array(statementNode, 2, 4)) {
            object(item, Set.of("text", "kind", "evidence_labels"), Set.of());
            statements.add(new Statement(string(item.get("text"), 1, 300), oneOf(item.get("kind"), STATEMENT_KINDS),
                    labels(item.get("evidence_labels"))));
        }
        JsonNode candidate = node.get("selected_candidate_id");
        if (!candidate.isNull()) {
            string(candidate);
        }
        oneOf(node.get("attack_angle"), ATTACK_ANGLES);
        return new TextDraft(oneOf(node.get("intensity"), INTENSITIES), string(node.get("headline"), 0, 30),
                List.copyOf(statements), statementNode.toString(), oneOf(node.get("banter_strategy"), BANTER_STRATEGIES),
                oneOf(node.get("source"), SOURCES));
    }

    private static Evaluation evaluation(JsonNode node) {
        object(node, Set.of("schema_version", "policy_version", "sentence_check", "sentencing_reason_check", "texts"),
                Set.of());
        constOne(node.get("schema_version"));
        List<TextEvaluation> texts = new ArrayList<>();
        for (JsonNode text : array(node.get("texts"), 1, 3)) {
            object(text, Set.of("intensity", "pass", "violations", "problem_sentences"), Set.of());
            violations(text.get("violations"));
            for (JsonNode sentence : array(text.get("problem_sentences"), 0, 10)) {
                string(sentence, 0, 300);
            }
            texts.add(new TextEvaluation(oneOf(text.get("intensity"), INTENSITIES), bool(text.get("pass"))));
        }
        return new Evaluation(oneOf(node.get("policy_version"), GUARDRAIL_VERSIONS),
                checkResult(node.get("sentence_check")), checkResult(node.get("sentencing_reason_check")),
                List.copyOf(texts));
    }

    private static boolean checkResult(JsonNode node) {
        object(node, Set.of("pass", "violations"), Set.of());
        violations(node.get("violations"));
        return bool(node.get("pass"));
    }

    private static void violations(JsonNode node) {
        for (JsonNode violation : array(node, 0, 20)) {
            object(violation, Set.of("code", "path", "evidence_labels", "explanation"), Set.of());
            oneOf(violation.get("code"), VIOLATION_CODES);
            string(violation.get("path"), 0, 200);
            labels(violation.get("evidence_labels"));
            string(violation.get("explanation"), 0, 300);
        }
    }

    private static List<String> labels(JsonNode node) {
        List<String> labels = new ArrayList<>();
        for (JsonNode label : array(node, 0, 16)) {
            labels.add(pattern(label, LABEL));
        }
        return List.copyOf(labels);
    }

    // ---- 스키마 원시 검사. 실패는 모두 422 INVALID_DRAFT ----

    private static JsonNode readTree(byte[] body) {
        if (body == null || body.length == 0) {
            throw invalid();
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (JacksonException e) {
            throw invalid();
        }
        if (root == null || !root.isObject()) {
            throw invalid();
        }
        return root;
    }

    /** required 가 비면 allowed 전부가 필수다. finalize-v1 은 모든 객체의 속성이 필수다 */
    private static void object(JsonNode node, Set<String> allowed, Set<String> optional) {
        if (node == null || !node.isObject()) {
            throw invalid();
        }
        Set<String> seen = new HashSet<>();
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            if (!allowed.contains(entry.getKey())) {
                throw invalid();
            }
            seen.add(entry.getKey());
        }
        for (String key : allowed) {
            if (!optional.contains(key) && !seen.contains(key)) {
                throw invalid();
            }
        }
    }

    private static List<JsonNode> array(JsonNode node, int min, int max) {
        if (node == null || !node.isArray() || node.size() < min || node.size() > max) {
            throw invalid();
        }
        List<JsonNode> items = new ArrayList<>(node.size());
        node.values().forEach(items::add);
        return items;
    }

    private static String string(JsonNode node) {
        if (node == null || !node.isString()) {
            throw invalid();
        }
        return node.stringValue();
    }

    private static String string(JsonNode node, int minLength, int maxLength) {
        String value = string(node);
        int length = value.codePointCount(0, value.length());
        if (length < minLength || length > maxLength) {
            throw invalid();
        }
        return value;
    }

    private static String oneOf(JsonNode node, Set<String> values) {
        String value = string(node);
        if (!values.contains(value)) {
            throw invalid();
        }
        return value;
    }

    private static String pattern(JsonNode node, Pattern pattern) {
        String value = string(node);
        if (!pattern.matcher(value).matches()) {
            throw invalid();
        }
        return value;
    }

    private static long integer(JsonNode node, long minimum) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong() || node.longValue() < minimum) {
            throw invalid();
        }
        return node.longValue();
    }

    private static void constOne(JsonNode node) {
        if (integer(node, 1) != 1) {
            throw invalid();
        }
    }

    private static boolean bool(JsonNode node) {
        if (node == null || !node.isBoolean()) {
            throw invalid();
        }
        return node.booleanValue();
    }

    static UUID parseUuid(String value) {
        UUID uuid = parseUuidOrNull(value);
        if (uuid == null) {
            throw invalid();
        }
        return uuid;
    }

    /** 표준 36자 UUID 가 아니면 null. UUID.fromString 은 "1-1-1-1-1" 같은 짧은 표기도 받아서 되돌려 비교한다 */
    static UUID parseUuidOrNull(String value) {
        if (value == null) {
            return null;
        }
        try {
            UUID uuid = UUID.fromString(value);
            return uuid.toString().equalsIgnoreCase(value) ? uuid : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static InternalApiException invalid() {
        return new InternalApiException(HttpStatus.UNPROCESSABLE_CONTENT, INVALID_DRAFT);
    }
}
