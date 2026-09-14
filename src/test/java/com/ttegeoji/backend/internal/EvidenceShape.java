package com.ttegeoji.backend.internal;

import tools.jackson.databind.JsonNode;

import java.time.OffsetDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * AI 저장소 ports/backend.py ResolveEvidenceResponse(extra="forbid")를 손으로 옮긴 모양 검사.
 * 키 집합이 정확히 같아야 하고(알 수 없는 필드 = 워커 거부), 타입·enum 을 본다. 모델이 바뀌면 여기를 같이 고친다.
 */
final class EvidenceShape {

    private static final Set<String> TOP = Set.of("sources", "aggregates", "room_rules", "recent_verdicts",
            "style_comments");
    private static final Set<String> SOURCE = Set.of("source_type", "source_id", "source_version", "payload", "scope");
    private static final Set<String> SCOPE = Set.of("visibility", "room_ids");
    private static final Set<String> AGGREGATES = Set.of("burn_rate", "tier", "no_spend_days",
            "repeat_same_category_30d", "excludes_post_id", "window", "rule_version");
    private static final Set<String> WINDOW = Set.of("start_at", "end_at");
    private static final Set<String> RULE = Set.of("room_id", "rule_id", "version", "text");
    private static final Set<String> RECENT = Set.of("post_id", "post_version", "category", "amount_krw", "reason",
            "result", "sentence", "judged_at", "scope");
    private static final Set<String> STYLE = Set.of("comment_id", "room_id", "content", "created_at");

    private EvidenceShape() {
    }

    static void assertValid(JsonNode n) {
        keys(n, TOP);
        for (String key : TOP) {
            assertThat(n.get(key).isArray() || key.equals("aggregates")).as(key).isTrue();
        }

        JsonNode sources = n.get("sources");
        for (int i = 0; i < sources.size(); i++) {
            JsonNode s = sources.get(i);
            keys(s, SOURCE);
            assertThat(s.get("source_type").isString()).isTrue();
            assertThat(s.get("source_id").isString()).isTrue();
            assertThat(s.get("source_version").isIntegralNumber()).isTrue();
            assertThat(s.get("payload").isObject()).isTrue();
            scope(s.get("scope"));
        }

        JsonNode a = n.get("aggregates");
        keys(a, AGGREGATES);
        assertThat(a.get("burn_rate").isNumber()).isTrue();
        assertThat(a.get("burn_rate").doubleValue()).isBetween(0.0, 1.0);
        assertThat(a.get("tier").isString()).isTrue();
        assertThat(a.get("no_spend_days").isIntegralNumber()).isTrue();
        assertThat(a.get("repeat_same_category_30d").isIntegralNumber()).isTrue();
        assertThat(a.get("excludes_post_id").isString()).isTrue();
        assertThat(a.get("rule_version").isIntegralNumber()).isTrue();
        keys(a.get("window"), WINDOW);
        dateTime(a.get("window").get("start_at"));
        dateTime(a.get("window").get("end_at"));

        JsonNode rules = n.get("room_rules");
        for (int i = 0; i < rules.size(); i++) {
            JsonNode r = rules.get(i);
            keys(r, RULE);
            assertThat(r.get("room_id").isString()).isTrue();
            assertThat(r.get("rule_id").isString()).isTrue();
            assertThat(r.get("version").isIntegralNumber()).isTrue();
            assertThat(r.get("text").isString()).isTrue();
        }

        JsonNode recent = n.get("recent_verdicts");
        for (int i = 0; i < recent.size(); i++) {
            JsonNode v = recent.get(i);
            keys(v, RECENT);
            assertThat(v.get("post_id").isString()).isTrue();
            assertThat(v.get("post_version").isIntegralNumber()).isTrue();
            assertThat(v.get("category").isString()).isTrue();
            assertThat(v.get("amount_krw").isIntegralNumber()).isTrue();
            assertThat(v.get("reason").isString() || v.get("reason").isNull()).isTrue();
            assertThat(v.get("result").isString()).isTrue();
            // 워커 모델은 sentence 를 non-null str 로 받는다
            assertThat(v.get("sentence").isString()).isTrue();
            dateTime(v.get("judged_at"));
            scope(v.get("scope"));
        }

        JsonNode style = n.get("style_comments");
        for (int i = 0; i < style.size(); i++) {
            keys(style.get(i), STYLE);
        }
    }

    private static void scope(JsonNode scope) {
        keys(scope, SCOPE);
        assertThat(scope.get("visibility").stringValue()).isIn("PUBLIC", "ROOMS", "PRIVATE");
        assertThat(scope.get("room_ids").isArray()).isTrue();
    }

    private static void keys(JsonNode node, Set<String> expected) {
        assertThat(node.isObject()).isTrue();
        assertThat(node.propertyNames()).containsExactlyInAnyOrderElementsOf(expected);
    }

    private static void dateTime(JsonNode node) {
        assertThat(node.isString()).isTrue();
        assertThatCode(() -> OffsetDateTime.parse(node.stringValue())).doesNotThrowAnyException();
    }
}
