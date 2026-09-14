package com.ttegeoji.backend.internal;

import tools.jackson.databind.JsonNode;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * AI 저장소 contracts/case-snapshot-v1.schema.json 을 손으로 옮긴 모양 검사. 스키마 라이브러리를 더하지 않으려고
 * required·additionalProperties false·enum·타입·범위를 직접 본다. 스키마가 바뀌면 여기를 같이 고친다.
 */
final class CaseSnapshotShape {

    static final List<String> TOP_REQUIRED = List.of("schema_version", "post_id", "author_id", "post_version", "item",
            "reason", "amount_krw", "category", "post_type", "created_at", "audience", "privacy_versions",
            "room_snapshots", "intake_result", "jury");
    static final List<String> TOP_OPTIONAL = List.of("verdict_final", "comment");

    private static final Set<String> CATEGORIES = Set.of("식비", "배달", "카페/간식", "교통/택시", "쇼핑/패션", "뷰티",
            "취미/여가", "술/유흥", "구독", "생활", "기타");
    private static final Set<String> INTENSITIES = Set.of("mild", "spicy", "hell");
    private static final Set<String> SENTENCES = Set.of("probation", "oneDay", "life");
    private static final Set<String> BANTER = Set.of("CHEAPER_ALTERNATIVE", "FREE_ALTERNATIVE", "DIY_REPLACEMENT",
            "PREMISE_REJECTION", "EXCUSE_STRIPPING", "NECESSITY_APPROVAL", "REPEAT_OFFENSE", "ROOM_RULE_CALLBACK");

    private CaseSnapshotShape() {
    }

    static void assertValid(JsonNode n) {
        object(n, "CaseSnapshot", TOP_REQUIRED, TOP_OPTIONAL);
        assertThat(n.get("schema_version").isIntegralNumber()).isTrue();
        assertThat(n.get("schema_version").intValue()).isEqualTo(1);
        string(n.get("post_id"), "post_id");
        string(n.get("author_id"), "author_id");
        integer(n.get("post_version"), "post_version", 1);
        assertThat(string(n.get("item"), "item")).hasSizeLessThanOrEqualTo(30);
        if (!n.get("reason").isNull()) {
            assertThat(string(n.get("reason"), "reason")).hasSizeLessThanOrEqualTo(200);
        }
        integer(n.get("amount_krw"), "amount_krw", 1);
        assertThat(string(n.get("category"), "category")).isIn(CATEGORIES);
        assertThat(string(n.get("post_type"), "post_type")).isIn("spent", "considering");
        dateTime(n.get("created_at"), "created_at");

        JsonNode audience = n.get("audience");
        object(audience, "audience", List.of("room_ids", "audience_version", "public_share_enabled"), List.of());
        assertThat(audience.get("room_ids").isArray()).isTrue();
        assertThat(audience.get("room_ids").size()).isLessThanOrEqualTo(50);
        Set<String> unique = new HashSet<>();
        audience.get("room_ids").forEach(id -> assertThat(unique.add(string(id, "room_ids[]"))).isTrue());
        integer(audience.get("audience_version"), "audience_version", 1);
        assertThat(audience.get("public_share_enabled").isBoolean()).isTrue();

        JsonNode privacy = n.get("privacy_versions");
        assertThat(privacy.isArray()).isTrue();
        assertThat(privacy.size()).isLessThanOrEqualTo(100);
        privacy.forEach(p -> {
            object(p, "privacy_versions[]", List.of("scope_key", "epoch"), List.of());
            string(p.get("scope_key"), "scope_key");
            integer(p.get("epoch"), "epoch", 0);
        });

        JsonNode rooms = n.get("room_snapshots");
        assertThat(rooms.isArray()).isTrue();
        assertThat(rooms.size()).isLessThanOrEqualTo(50);
        rooms.forEach(r -> {
            object(r, "room_snapshots[]", List.of("room_id", "intensity", "rule_version"), List.of());
            string(r.get("room_id"), "room_id");
            assertThat(string(r.get("intensity"), "intensity")).isIn(INTENSITIES);
            integer(r.get("rule_version"), "rule_version", 0);
        });

        if (!n.get("intake_result").isNull()) {
            intakeResult(n.get("intake_result"));
        }
        if (!n.get("jury").isNull()) {
            jury(n.get("jury"));
        }
        if (n.has("verdict_final") && !n.get("verdict_final").isNull()) {
            verdictFinal(n.get("verdict_final"));
        }
        if (n.has("comment") && !n.get("comment").isNull()) {
            comment(n.get("comment"));
        }
    }

    private static void intakeResult(JsonNode n) {
        object(n, "intake_result", List.of("schema_version", "mode", "status", "item_review", "message",
                "category_review", "injection_detected", "intake_source"), List.of());
        assertThat(n.get("schema_version").intValue()).isEqualTo(1);
        assertThat(string(n.get("mode"), "mode")).isIn("INITIAL", "FINAL_CHECK");
        assertThat(string(n.get("status"), "status")).isIn("PASS", "NEEDS_CLARIFICATION", "BLOCKED");
        object(n.get("item_review"), "item_review", List.of("status", "suggested_item"), List.of());
        object(n.get("category_review"), "category_review", List.of("status", "suggested_category", "confidence"),
                List.of());
        assertThat(n.get("injection_detected").isBoolean()).isTrue();
        assertThat(string(n.get("intake_source"), "intake_source")).isIn("AI", "FALLBACK");
    }

    private static void jury(JsonNode n) {
        object(n, "jury", List.of("verdict_id", "verdict_version", "result", "vote_counts", "guilty_ratio",
                "confirmed_at", "deadline_at", "policy", "target_intensities", "default_intensity"), List.of());
        string(n.get("verdict_id"), "verdict_id");
        integer(n.get("verdict_version"), "verdict_version", 1);
        assertThat(string(n.get("result"), "result")).isIn("guilty", "notGuilty", "agree", "disagree");
        assertThat(n.get("vote_counts").isObject()).isTrue();
        n.get("vote_counts").properties().forEach(e -> integer(e.getValue(), "vote_counts." + e.getKey(), 0));
        assertThat(n.get("guilty_ratio").isNumber()).isTrue();
        assertThat(n.get("guilty_ratio").doubleValue()).isBetween(0.0, 1.0);
        dateTime(n.get("confirmed_at"), "confirmed_at");
        dateTime(n.get("deadline_at"), "deadline_at");

        JsonNode policy = n.get("policy");
        object(policy, "policy", List.of("version", "allowed_sentences", "fallback_sentence", "reason_required"),
                List.of());
        string(policy.get("version"), "policy.version");
        assertThat(policy.get("allowed_sentences").size()).isBetween(1, 3);
        policy.get("allowed_sentences").forEach(a -> {
            object(a, "allowed_sentences[]", List.of("code", "rank"), List.of());
            assertThat(string(a.get("code"), "code")).isIn(SENTENCES);
            integer(a.get("rank"), "rank", 1);
        });
        assertThat(string(policy.get("fallback_sentence"), "fallback_sentence")).isIn(SENTENCES);
        assertThat(policy.get("reason_required").isBoolean()).isTrue();

        JsonNode targets = n.get("target_intensities");
        assertThat(targets.size()).isBetween(1, 3);
        Set<String> unique = new HashSet<>();
        targets.forEach(t -> assertThat(unique.add(string(t, "target_intensities[]"))).isTrue());
        assertThat(unique).isSubsetOf(INTENSITIES);
        assertThat(string(n.get("default_intensity"), "default_intensity")).isIn(INTENSITIES);
    }

    private static void verdictFinal(JsonNode n) {
        object(n, "verdict_final", List.of("sentence", "sentence_source", "sentencing_reason", "reason_source",
                "applied_intensity", "banter_strategy"), List.of());
        nullableString(n.get("sentence"), "sentence");
        if (!n.get("sentence_source").isNull()) {
            assertThat(string(n.get("sentence_source"), "sentence_source")).isIn("AI", "RULE");
        }
        if (!n.get("sentencing_reason").isNull()) {
            assertThat(string(n.get("sentencing_reason"), "sentencing_reason")).hasSizeLessThanOrEqualTo(100);
        }
        if (!n.get("reason_source").isNull()) {
            assertThat(string(n.get("reason_source"), "reason_source")).isIn("AI", "TEMPLATE");
        }
        assertThat(string(n.get("applied_intensity"), "applied_intensity")).isIn(INTENSITIES);
        if (!n.get("banter_strategy").isNull()) {
            assertThat(string(n.get("banter_strategy"), "banter_strategy")).isIn(BANTER);
        }
    }

    private static void comment(JsonNode n) {
        object(n, "comment", List.of("comment_id", "version", "room_id", "post_id", "post_status", "author_id",
                "content", "created_at"), List.of());
        integer(n.get("version"), "comment.version", 1);
        assertThat(string(n.get("content"), "content")).hasSizeLessThanOrEqualTo(1000);
        dateTime(n.get("created_at"), "comment.created_at");
    }

    private static void object(JsonNode n, String name, List<String> required, List<String> optional) {
        assertThat(n).as(name).isNotNull();
        assertThat(n.isObject()).as(name + " 은 객체").isTrue();
        Set<String> keys = new HashSet<>(n.propertyNames());
        assertThat(keys).as(name + " required 키").containsAll(required);
        Set<String> allowed = new HashSet<>(required);
        allowed.addAll(optional);
        assertThat(keys).as(name + " 알려진 키 밖 없음").isSubsetOf(allowed);
    }

    private static String string(JsonNode n, String name) {
        assertThat(n).as(name).isNotNull();
        assertThat(n.isString()).as(name + " 은 문자열").isTrue();
        return n.stringValue();
    }

    private static void nullableString(JsonNode n, String name) {
        assertThat(n.isNull() || n.isString()).as(name + " 은 문자열 또는 null").isTrue();
    }

    private static void integer(JsonNode n, String name, long minimum) {
        assertThat(n).as(name).isNotNull();
        assertThat(n.isIntegralNumber()).as(name + " 은 정수").isTrue();
        assertThat(n.longValue()).as(name + " 최소값").isGreaterThanOrEqualTo(minimum);
    }

    private static void dateTime(JsonNode n, String name) {
        String value = string(n, name);
        assertThatCode(() -> OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                .as(name + " 은 RFC3339").doesNotThrowAnyException();
    }
}
