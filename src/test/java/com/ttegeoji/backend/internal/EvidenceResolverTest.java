package com.ttegeoji.backend.internal;

import com.ttegeoji.backend.config.InternalApiException;
import com.ttegeoji.backend.internal.dto.EvidenceCandidate;
import com.ttegeoji.backend.internal.dto.EvidenceInclude;
import com.ttegeoji.backend.internal.dto.ResolveEvidenceRequest;
import com.ttegeoji.backend.jobs.JobKind;
import com.ttegeoji.backend.jobs.JobQueries;
import com.ttegeoji.backend.jobs.JobRow;
import com.ttegeoji.backend.support.PostgresContainerSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

// 시각을 고정한 행으로 창 경계를 본다. 행은 테스트 트랜잭션 안에서만 보인다(롤백).
@SpringBootTest
@Transactional
class EvidenceResolverTest extends PostgresContainerSupport {

    private static final UUID GENERATION = UUID.randomUUID();
    private static final String LEASE = "now() + interval '30 seconds'";
    private static final String TAXI = "교통/택시";
    private static final String CASE_AT = "2026-09-15 12:00:00+09";
    private static final List<EvidenceInclude> ALL = List.of(EvidenceInclude.rules, EvidenceInclude.aggregates,
            EvidenceInclude.recent_verdicts, EvidenceInclude.style_comments);

    @Autowired
    private EvidenceResolver resolver;
    @Autowired
    private JobQueries jobQueries;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ObjectMapper objectMapper;

    private InternalFixtures fx;
    private UUID author;
    private UUID roomA;
    private UUID roomB;
    private UUID casePost;

    @BeforeEach
    void setUp() {
        fx = new InternalFixtures(jdbcTemplate);
        author = fx.profile();
        roomA = fx.room(author, "spicy", 1, "택시 금지", "배달 금지");
        roomB = fx.room(author, "mild", 1);
        casePost = fx.post(author, "spent", TAXI, 12000, CASE_AT);
        fx.share(casePost, roomA);
    }

    private JsonNode resolve(List<EvidenceCandidate> candidates, List<EvidenceInclude> include) {
        UUID jobId = fx.runningJob(JobKind.PREPARE, InternalFixtures.preparePayload(casePost), GENERATION, LEASE);
        JobRow job = jobQueries.findJob(jobId).orElseThrow();
        JsonNode n = objectMapper.readTree(objectMapper.writeValueAsString(
                resolver.resolve(job, new ResolveEvidenceRequest(candidates, include))));
        EvidenceShape.assertValid(n);
        return n;
    }

    private UUID sharedPost(UUID owner, String createdAt, UUID... rooms) {
        UUID post = fx.post(owner, "spent", TAXI, 5000, createdAt);
        for (UUID room : rooms) {
            fx.share(post, room);
        }
        return post;
    }

    private static EvidenceCandidate candidate(String type, Object id, long version, double score) {
        return new EvidenceCandidate(type, id.toString(), version, score);
    }

    private static List<String> texts(JsonNode array, String key) {
        List<String> values = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            values.add(array.get(i).get(key).stringValue());
        }
        return values;
    }

    private static OffsetDateTime at(String iso) {
        return OffsetDateTime.parse(iso);
    }

    @Test
    @DisplayName("10 §4.2 다른 방·비공개·다른 작성자·삭제·현재 사건·모르는 source_type 후보 제외, PUBLIC 은 통과, score 내림차순")
    void sourcesFilteredByAuthorRoomsAndPolicy() {
        String before = "2026-09-10 12:00:00+09";
        UUID inRoomA = sharedPost(author, before, roomA);
        UUID inRoomsAB = sharedPost(author, before, roomA, roomB);
        UUID onlyRoomB = sharedPost(author, before, roomB);
        UUID privatePost = sharedPost(author, before);
        UUID publicPost = sharedPost(author, before);
        jdbcTemplate.update("UPDATE posts SET public_share_enabled = true WHERE id = ?", publicPost);
        UUID otherAuthor = sharedPost(fx.profile(), before, roomA);
        UUID deleted = sharedPost(author, before, roomA);
        fx.deletePost(deleted);

        JsonNode sources = resolve(List.of(
                candidate("POST", inRoomA, 1, 0.5),
                candidate("POST", inRoomsAB, 1, 0.7),
                candidate("POST", onlyRoomB, 1, 0.99),
                candidate("POST", privatePost, 1, 0.98),
                candidate("POST", publicPost, 1, 0.9),
                candidate("POST", otherAuthor, 1, 0.97),
                candidate("POST", deleted, 1, 0.96),
                candidate("POST", casePost, 1, 0.95),
                candidate("RULE", "0", 1, 0.94),
                candidate("POST", "not-a-uuid", 1, 0.93)), ALL).get("sources");

        assertThat(texts(sources, "source_id"))
                .containsExactly(publicPost.toString(), inRoomsAB.toString(), inRoomA.toString());
        assertThat(sources.get(0).get("scope").get("visibility").stringValue()).isEqualTo("PUBLIC");
        JsonNode roomsScope = sources.get(2).get("scope");
        assertThat(roomsScope.get("visibility").stringValue()).isEqualTo("ROOMS");
        assertThat(roomsScope.get("room_ids").get(0).stringValue()).isEqualTo(roomA.toString());
        JsonNode payload = sources.get(2).get("payload");
        assertThat(payload.propertyNames()).containsExactlyInAnyOrder("category", "amount_krw", "reason", "spent_at");
        assertThat(payload.get("amount_krw").intValue()).isEqualTo(5000);
        assertThat(at(payload.get("spent_at").stringValue())).isEqualTo(at("2026-09-10T12:00:00+09:00"));
    }

    @Test
    @DisplayName("10 §4.2 stale 버전 후보 제외 — posts.version·verdict_version 불일치, FINAL 아닌 평결")
    void staleCandidatesExcluded() {
        String before = "2026-09-10 12:00:00+09";
        UUID bumped = sharedPost(author, before, roomA);
        fx.setPostVersion(bumped, 2);
        UUID finalPost = sharedPost(author, before, roomA);
        fx.vote(finalPost, roomA, "guilty");
        fx.vote(finalPost, roomA, "guilty");
        fx.vote(finalPost, roomA, "guilty");
        fx.vote(finalPost, roomA, "notGuilty");
        UUID finalVerdict = fx.verdict(finalPost, "guilty");
        fx.finalizeVerdict(finalVerdict, "oneDay", "AI", "사유", "AI", "spicy");
        UUID pendingPost = sharedPost(author, before, roomA);
        UUID pendingVerdict = fx.verdict(pendingPost, "guilty");

        JsonNode sources = resolve(List.of(
                candidate("POST", bumped, 1, 0.9),
                candidate("VERDICT", finalVerdict, 2, 0.8),
                candidate("VERDICT", pendingVerdict, 1, 0.7),
                candidate("VERDICT", finalVerdict, 1, 0.6)), ALL).get("sources");

        assertThat(sources.size()).isEqualTo(1);
        JsonNode source = sources.get(0);
        assertThat(source.get("source_type").stringValue()).isEqualTo("VERDICT");
        assertThat(source.get("source_id").stringValue()).isEqualTo(finalVerdict.toString());
        assertThat(source.get("source_version").intValue()).isEqualTo(1);
        JsonNode payload = source.get("payload");
        assertThat(payload.propertyNames()).containsExactlyInAnyOrder("result", "guilty_ratio", "sentence");
        assertThat(payload.get("result").stringValue()).isEqualTo("guilty");
        assertThat(payload.get("guilty_ratio").doubleValue()).isEqualTo(0.75);
        assertThat(payload.get("sentence").stringValue()).isEqualTo("oneDay");
    }

    @Test
    @DisplayName("10 §4.2 §14 burn_rate 0..1 — KST 달력월 1일~created_at 삭제 안 된 spent 합(현재 사건 포함) ÷ monthly_budget, tier")
    void burnRateIsRatioOfMonthlyBudget() {
        fx.setMonthlyBudget(author, 100_000);
        fx.post(author, "spent", "식비", 20_000, "2026-09-01 00:00:00+09");
        fx.post(author, "spent", "식비", 50_000, "2026-08-31 23:59:59+09");
        fx.post(author, "considering", "식비", 30_000, "2026-09-10 00:00:00+09");
        fx.deletePost(fx.post(author, "spent", "식비", 30_000, "2026-09-12 00:00:00+09"));
        fx.post(author, "spent", "식비", 40_000, "2026-09-15 12:00:01+09");
        fx.post(fx.profile(), "spent", "식비", 40_000, "2026-09-10 00:00:00+09");

        JsonNode aggregates = resolve(List.of(), ALL).get("aggregates");

        // 20,000 + 현재 사건 12,000
        assertThat(aggregates.get("burn_rate").doubleValue()).isEqualTo(0.32);
        assertThat(aggregates.get("tier").stringValue()).isEqualTo("flower");
        assertThat(aggregates.get("no_spend_days").intValue()).isZero();
    }

    @Test
    @DisplayName("10 §4.2 burn_rate 는 1 을 넘지 않고 budget 0 이면 0, tier 구간")
    void burnRateClampAndTier() {
        fx.setMonthlyBudget(author, 10_000);
        JsonNode over = resolve(List.of(), ALL).get("aggregates");
        assertThat(over.get("burn_rate").doubleValue()).isEqualTo(1.0);
        assertThat(over.get("tier").stringValue()).isEqualTo("penniless");

        fx.setMonthlyBudget(author, 0);
        JsonNode zero = resolve(List.of(), ALL).get("aggregates");
        assertThat(zero.get("burn_rate").doubleValue()).isEqualTo(0.0);
        assertThat(zero.get("tier").stringValue()).isEqualTo("king");

        assertThat(EvidenceResolver.tier(0.2499)).isEqualTo("king");
        assertThat(EvidenceResolver.tier(0.25)).isEqualTo("flower");
        assertThat(EvidenceResolver.tier(0.5)).isEqualTo("hardcore");
        assertThat(EvidenceResolver.tier(0.8)).isEqualTo("penniless");
    }

    @Test
    @DisplayName("10 §4.2 반복 집계 — 현재 사건 제외, [created_at−30일, created_at), 같은 카테고리 spent, window·excludes_post_id")
    void repeatSameCategoryWindow() {
        fx.post(author, "spent", TAXI, 5000, "2026-08-16 12:00:00+09");
        fx.post(author, "spent", TAXI, 5000, "2026-09-01 00:00:00+09");
        fx.post(author, "spent", TAXI, 5000, "2026-08-16 11:59:59+09");
        fx.post(author, "spent", "식비", 5000, "2026-09-01 00:00:00+09");
        fx.post(author, "considering", TAXI, 5000, "2026-09-01 00:00:00+09");
        fx.post(fx.profile(), "spent", TAXI, 5000, "2026-09-01 00:00:00+09");
        fx.post(author, "spent", TAXI, 5000, "2026-09-15 12:00:00+09");
        fx.post(author, "spent", TAXI, 5000, "2026-09-15 12:00:01+09");
        fx.deletePost(fx.post(author, "spent", TAXI, 5000, "2026-09-02 00:00:00+09"));

        JsonNode aggregates = resolve(List.of(), ALL).get("aggregates");

        assertThat(aggregates.get("repeat_same_category_30d").intValue()).isEqualTo(2);
        assertThat(aggregates.get("excludes_post_id").stringValue()).isEqualTo(casePost.toString());
        assertThat(at(aggregates.get("window").get("end_at").stringValue())).isEqualTo(at("2026-09-15T12:00:00+09:00"));
        assertThat(at(aggregates.get("window").get("start_at").stringValue())).isEqualTo(at("2026-08-16T12:00:00+09:00"));
    }

    @Test
    @DisplayName("10 §4.2 aggregates.rule_version 은 공유 방 rule_version 최댓값, room_rules 는 {room_id, 인덱스, rule_version, text}")
    void ruleVersionAndRoomRules() {
        jdbcTemplate.update("UPDATE rooms SET rule_version = 5 WHERE id = ?", roomA);
        UUID roomC = fx.room(author, "hell", 3, "술 금지");
        fx.share(casePost, roomC);

        JsonNode n = resolve(List.of(), ALL);

        assertThat(n.get("aggregates").get("rule_version").intValue()).isEqualTo(5);
        JsonNode rules = n.get("room_rules");
        assertThat(rules.size()).isEqualTo(3);
        assertThat(texts(rules, "text")).containsExactlyInAnyOrder("택시 금지", "배달 금지", "술 금지");
        for (int i = 0; i < rules.size(); i++) {
            JsonNode rule = rules.get(i);
            if (rule.get("text").stringValue().equals("배달 금지")) {
                assertThat(rule.get("room_id").stringValue()).isEqualTo(roomA.toString());
                assertThat(rule.get("rule_id").stringValue()).isEqualTo("1");
                assertThat(rule.get("version").intValue()).isEqualTo(5);
            }
            if (rule.get("text").stringValue().equals("술 금지")) {
                assertThat(rule.get("rule_id").stringValue()).isEqualTo("0");
                assertThat(rule.get("version").intValue()).isEqualTo(3);
            }
        }
    }

    @Test
    @DisplayName("10 §4.2 공유 방이 없으면 rule_version 1")
    void ruleVersionDefaultsToOne() {
        jdbcTemplate.update("DELETE FROM post_rooms WHERE post_id = ?", casePost);

        assertThat(resolve(List.of(), ALL).get("aggregates").get("rule_version").intValue()).isEqualTo(1);
    }

    @Test
    @DisplayName("10 §4.2 recent_verdicts — 같은 작성자 30일 FINAL 유죄만, scope 통과, 최근 순, verdict_id 없음")
    void recentVerdictsSelection() {
        UUID older = sharedPost(author, "2026-09-09 12:00:00+09", roomA);
        fx.finalizeVerdict(fx.verdict(older, "guilty", "2026-09-10 12:00:00+09"), "oneDay", "AI", "사유", "AI", "spicy");
        UUID newer = sharedPost(author, "2026-09-11 12:00:00+09", roomA);
        fx.finalizeVerdict(fx.verdict(newer, "guilty", "2026-09-12 12:00:00+09"), "probation", "RULE", null, "TEMPLATE",
                "spicy");
        UUID notGuilty = sharedPost(author, "2026-09-11 12:00:00+09", roomA);
        fx.finalizeVerdict(fx.verdict(notGuilty, "notGuilty", "2026-09-12 12:00:00+09"), null, "RULE", null,
                "TEMPLATE", "spicy");
        UUID outOfWindow = sharedPost(author, "2026-08-01 12:00:00+09", roomA);
        fx.finalizeVerdict(fx.verdict(outOfWindow, "guilty", "2026-08-16 11:59:59+09"), "oneDay", "AI", "사유", "AI",
                "spicy");
        UUID atCaseTime = sharedPost(author, "2026-09-14 12:00:00+09", roomA);
        fx.finalizeVerdict(fx.verdict(atCaseTime, "guilty", CASE_AT), "oneDay", "AI", "사유", "AI", "spicy");
        UUID pending = sharedPost(author, "2026-09-11 12:00:00+09", roomA);
        fx.verdict(pending, "guilty", "2026-09-12 12:00:00+09");
        UUID roomBOnly = sharedPost(author, "2026-09-11 12:00:00+09", roomB);
        fx.finalizeVerdict(fx.verdict(roomBOnly, "guilty", "2026-09-12 12:00:00+09"), "oneDay", "AI", "사유", "AI",
                "spicy");
        UUID otherAuthor = sharedPost(fx.profile(), "2026-09-11 12:00:00+09", roomA);
        fx.finalizeVerdict(fx.verdict(otherAuthor, "guilty", "2026-09-12 12:00:00+09"), "oneDay", "AI", "사유", "AI",
                "spicy");

        JsonNode recent = resolve(List.of(), ALL).get("recent_verdicts");

        assertThat(texts(recent, "post_id")).containsExactly(newer.toString(), older.toString());
        JsonNode first = recent.get(0);
        assertThat(first.has("verdict_id")).isFalse();
        assertThat(first.get("sentence").stringValue()).isEqualTo("probation");
        assertThat(first.get("result").stringValue()).isEqualTo("guilty");
        assertThat(first.get("category").stringValue()).isEqualTo(TAXI);
        assertThat(first.get("post_version").intValue()).isEqualTo(1);
        assertThat(at(first.get("judged_at").stringValue())).isEqualTo(at("2026-09-12T12:00:00+09:00"));
        assertThat(first.get("scope").get("visibility").stringValue()).isEqualTo("ROOMS");
    }

    @Test
    @DisplayName("10 §4.2 다섯 키 늘 존재, style_comments 는 빈 배열, include 에 없는 room_rules·recent_verdicts 는 [] 이고 aggregates 는 채움")
    void fiveKeysAndIncludeHandling() {
        UUID older = sharedPost(author, "2026-09-09 12:00:00+09", roomA);
        fx.finalizeVerdict(fx.verdict(older, "guilty", "2026-09-10 12:00:00+09"), "oneDay", "AI", "사유", "AI", "spicy");

        JsonNode full = resolve(List.of(), ALL);
        assertThat(full.propertyNames()).containsExactlyInAnyOrder("sources", "aggregates", "room_rules",
                "recent_verdicts", "style_comments");
        assertThat(full.get("style_comments").isArray()).isTrue();
        assertThat(full.get("style_comments").isEmpty()).isTrue();
        assertThat(full.get("room_rules").isEmpty()).isFalse();
        assertThat(full.get("recent_verdicts").isEmpty()).isFalse();

        JsonNode narrow = resolve(List.of(), List.of());
        assertThat(narrow.propertyNames()).containsExactlyInAnyOrder("sources", "aggregates", "room_rules",
                "recent_verdicts", "style_comments");
        assertThat(narrow.get("room_rules").isEmpty()).isTrue();
        assertThat(narrow.get("recent_verdicts").isEmpty()).isTrue();
        assertThat(narrow.get("aggregates").get("excludes_post_id").stringValue()).isEqualTo(casePost.toString());
    }

    @Test
    @DisplayName("10 §4.1·§4.2 사건 게시물이 삭제됐으면 resolve 도 404 NOT_FOUND")
    void deletedCaseNotFound() {
        fx.deletePost(casePost);
        UUID jobId = fx.runningJob(JobKind.PREPARE, InternalFixtures.preparePayload(casePost), GENERATION, LEASE);
        JobRow job = jobQueries.findJob(jobId).orElseThrow();

        Throwable thrown = catchThrowable(() -> resolver.resolve(job, new ResolveEvidenceRequest(List.of(), ALL)));

        assertThat(thrown).isInstanceOf(InternalApiException.class);
        assertThat(((InternalApiException) thrown).getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(((InternalApiException) thrown).getCode()).isEqualTo("NOT_FOUND");
    }
}
