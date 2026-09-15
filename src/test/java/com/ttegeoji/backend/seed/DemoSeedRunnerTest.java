package com.ttegeoji.backend.seed;

import com.ttegeoji.backend.ai.IntakeClient;
import com.ttegeoji.backend.ai.IntakeClient.CategoryReview;
import com.ttegeoji.backend.ai.IntakeClient.IntakeRequest;
import com.ttegeoji.backend.ai.IntakeClient.IntakeResult;
import com.ttegeoji.backend.ai.IntakeClient.ItemReview;
import com.ttegeoji.backend.ai.IntakeClient.Status;
import com.ttegeoji.backend.comment.PostCommentService;
import com.ttegeoji.backend.domain.enums.IntakeSource;
import com.ttegeoji.backend.repository.SubmissionRepository;
import com.ttegeoji.backend.seed.DemoSeedRunner.SeedResult;
import com.ttegeoji.backend.submission.PostCreator;
import com.ttegeoji.backend.submission.SubmissionQueries;
import com.ttegeoji.backend.submission.SubmissionService;
import com.ttegeoji.backend.support.PostgresContainerSupport;
import com.ttegeoji.backend.verdict.JuryQueries.PendingVerdict;
import com.ttegeoji.backend.verdict.SentenceGate;
import com.ttegeoji.backend.verdictview.PostVoteService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// 러너를 직접 만든다(seed 프로필 빈은 test 에 없다). IntakeClient 는 목이라 실제 AI API 를 부르지 않고, 워커도 없다.
// 서비스가 스스로 커밋하므로 테스트마다 새 사용자 UUID 4개로 돌리고 수량은 그 사용자 기준으로 센다
@SpringBootTest
@ExtendWith(OutputCaptureExtension.class)
class DemoSeedRunnerTest extends PostgresContainerSupport {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PostCreator postCreator;
    @Autowired
    private SubmissionQueries submissionQueries;
    @Autowired
    private SubmissionRepository submissionRepository;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private PostVoteService voteService;
    @Autowired
    private PostCommentService commentService;
    @Autowired
    private SentenceGate sentenceGate;
    @Autowired
    private ApplicationContext context;

    private DemoSeedRunner runner(Function<IntakeRequest, IntakeResult> intake) {
        IntakeClient client = mock(IntakeClient.class);
        when(client.call(any())).thenAnswer(inv -> intake.apply(inv.getArgument(0)));
        SubmissionService submissions = new SubmissionService(client, postCreator, submissionQueries,
                submissionRepository, entityManager, transactionManager);
        return new DemoSeedRunner(new SeedQueries(jdbc), submissions, voteService, commentService, "");
    }

    // "심야 택시" 는 질문(NEEDS_CLARIFICATION)을 받아 PROCEED 로 등록되는 길을 탄다
    private DemoSeedRunner runner() {
        return runner(req -> "심야 택시".equals(req.item()) && req.mode() == IntakeClient.Mode.INITIAL
                ? result(req, Status.NEEDS_CLARIFICATION, "막차가 끊긴 시각이 몇 시였나요?")
                : result(req, Status.PASS, ""));
    }

    private static IntakeResult result(IntakeRequest req, Status status, String message) {
        return new IntakeResult(req.mode(), status, new ItemReview("OK", null), message,
                new CategoryReview("OK", null, 0.9), false, IntakeSource.AI);
    }

    private static List<UUID> users() {
        return Stream.generate(UUID::randomUUID).limit(4).toList();
    }

    private record Counts(long profiles, long rooms, long posts, long comments, long verdicts, long votes,
                          long submissions) {
    }

    private Counts counts(List<UUID> users) {
        String ids = array(users);
        return new Counts(
                count("SELECT count(*) FROM profiles WHERE id::text = ANY(CAST(? AS text[]))", ids),
                count("SELECT count(*) FROM rooms WHERE created_by::text = ANY(CAST(? AS text[]))", ids),
                count("SELECT count(*) FROM posts WHERE author_id::text = ANY(CAST(? AS text[]))", ids),
                count("""
                        SELECT count(*) FROM post_comments c JOIN posts p ON p.id = c.post_id
                         WHERE p.author_id::text = ANY(CAST(? AS text[])) AND c.deleted_at IS NULL""", ids),
                count("""
                        SELECT count(*) FROM verdicts v JOIN posts p ON p.id = v.post_id
                         WHERE p.author_id::text = ANY(CAST(? AS text[]))""", ids),
                count("SELECT count(*) FROM votes WHERE voter_id::text = ANY(CAST(? AS text[]))", ids),
                count("SELECT count(*) FROM submissions WHERE actor_id::text = ANY(CAST(? AS text[]))", ids));
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private static String array(List<?> values) {
        return values.stream().map(Object::toString).collect(Collectors.joining(",", "{", "}"));
    }

    @Test
    @DisplayName("10 §12 수량 — 방 3·사용자 4·게시물 12(카테고리 6 × 2)·댓글 20(21자 이상)·평결 10")
    void seedsContractCounts() {
        List<UUID> users = users();
        SeedResult result = runner().seed(users);

        Counts counts = counts(users);
        assertThat(counts.profiles()).isEqualTo(4);
        assertThat(counts.rooms()).isEqualTo(3);
        assertThat(counts.posts()).isEqualTo(12);
        assertThat(counts.comments()).isEqualTo(20);
        assertThat(counts.verdicts()).isEqualTo(10);
        assertThat(result.postIds()).hasSize(12).doesNotHaveDuplicates();

        Map<String, Long> categories = new HashMap<>();
        jdbc.query("SELECT category, count(*) AS n FROM posts WHERE author_id::text = ANY(CAST(? AS text[])) GROUP BY category",
                rs -> {
                    categories.put(rs.getString("category"), rs.getLong("n"));
                }, array(users));
        assertThat(categories).hasSize(6).allSatisfy((category, n) -> assertThat(n).isEqualTo(2));

        List<Map<String, Object>> rooms = jdbc.queryForList("""
                SELECT name, spice_level::text AS spice, vote_deadline_minutes, cardinality(rules) AS rules
                  FROM rooms WHERE created_by = ? ORDER BY created_at, id""", users.getFirst());
        assertThat(rooms).extracting(r -> r.get("name")).containsExactlyInAnyOrderElementsOf(DemoSeedRunner.roomNames());
        assertThat(rooms).extracting(r -> r.get("spice")).containsExactlyInAnyOrder("mild", "spicy", "hell");
        assertThat(rooms).allSatisfy(r -> {
            assertThat(r.get("vote_deadline_minutes")).isEqualTo(30);
            assertThat((Integer) r.get("rules")).isBetween(3, 5);
        });

        List<String> contents = jdbc.queryForList("""
                SELECT c.content FROM post_comments c JOIN posts p ON p.id = c.post_id
                 WHERE p.author_id::text = ANY(CAST(? AS text[]))""", String.class, array(users));
        assertThat(contents).allSatisfy(c -> assertThat(c.codePointCount(0, c.length())).isBetween(21, 200));
        // 댓글은 판결 확정 대상 글에만
        assertThat(count("""
                SELECT count(*) FROM post_comments c JOIN posts p ON p.id = c.post_id
                 WHERE p.author_id::text = ANY(CAST(? AS text[]))
                   AND NOT EXISTS (SELECT 1 FROM verdicts v WHERE v.post_id = p.id)""", array(users))).isZero();
    }

    @Test
    @DisplayName("10 §12 확정 대상 10 = 유죄 6·무죄 2·동의 1·기각 1, 투표 중 2 · 스타벅스 두 건은 유죄")
    void verdictDistribution() {
        List<UUID> users = users();
        SeedResult result = runner().seed(users);

        Map<String, Long> distribution = new HashMap<>();
        jdbc.query("""
                SELECT v.jury_result::text AS result, count(*) AS n FROM verdicts v JOIN posts p ON p.id = v.post_id
                 WHERE p.author_id::text = ANY(CAST(? AS text[])) GROUP BY v.jury_result""",
                rs -> {
                    distribution.put(rs.getString("result"), rs.getLong("n"));
                }, array(users));
        assertThat(distribution).containsExactlyInAnyOrderEntriesOf(
                Map.of("guilty", 6L, "notGuilty", 2L, "agree", 1L, "disagree", 1L));
        assertThat(count("""
                SELECT count(*) FROM posts p WHERE p.author_id::text = ANY(CAST(? AS text[]))
                   AND NOT EXISTS (SELECT 1 FROM verdicts v WHERE v.post_id = p.id)""", array(users))).isEqualTo(2);

        assertThat(result.starbucksPostIds()).hasSize(2);
        assertThat(result.starbucksVerdictIds()).hasSize(2);
        for (int i = 0; i < 2; i++) {
            Map<String, Object> row = jdbc.queryForMap("""
                    SELECT p.author_id, p.item, p.amount_krw, p.category, p.reason, v.id AS verdict_id,
                           v.jury_result::text AS result
                      FROM posts p JOIN verdicts v ON v.post_id = p.id WHERE p.id = ?""", result.starbucksPostIds().get(i));
            assertThat(row.get("author_id")).isEqualTo(users.getFirst());
            assertThat(row.get("item")).isEqualTo("스타벅스");
            assertThat(row.get("amount_krw")).isEqualTo(6100);
            assertThat(row.get("category")).isEqualTo("카페/간식");
            assertThat(row.get("reason")).isNull();
            assertThat(row.get("verdict_id")).isEqualTo(result.starbucksVerdictIds().get(i));
            assertThat(row.get("result")).isEqualTo("guilty");
        }
        // 스타벅스 두 건은 데모 C 방(매운맛)에 공유
        assertThat(jdbc.queryForList("SELECT DISTINCT room_id FROM post_rooms WHERE post_id IN (?, ?)", UUID.class,
                result.starbucksPostIds().get(0), result.starbucksPostIds().get(1))).containsExactly(result.demoRoomId());
        assertThat(jdbc.queryForObject("SELECT spice_level::text FROM rooms WHERE id = ?", String.class,
                result.demoRoomId())).isEqualTo("spicy");
    }

    @Test
    @DisplayName("10 §12 데모 C 사용자는 세 방 모두 멤버")
    void demoUserInAllRooms() {
        List<UUID> users = users();
        SeedResult result = runner().seed(users);

        assertThat(result.demoUserId()).isEqualTo(users.getFirst());
        assertThat(result.roomIds()).hasSize(3);
        assertThat(jdbc.queryForList("SELECT room_id FROM room_members WHERE user_id = ?", UUID.class, users.getFirst()))
                .containsExactlyInAnyOrderElementsOf(result.roomIds());
    }

    @Test
    @DisplayName("10 §12 두 번 실행해도 수량·식별자 불변(있으면 건너뜀)")
    void idempotent() {
        List<UUID> users = users();
        DemoSeedRunner runner = runner();
        SeedResult first = runner.seed(users);
        Counts before = counts(users);

        SeedResult second = runner.seed(users);

        assertThat(counts(users)).isEqualTo(before);
        assertThat(before.submissions()).isEqualTo(12);
        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("10 §12·§3 확정 대상 10 에 SENTENCE job 10개 — PREPARE 종료 뒤 게이트가 넣는다(워커 없이 job 행까지)")
    void sentenceJobsForConfirmedVerdicts() {
        List<UUID> users = users();
        SeedResult result = runner().seed(users);
        String posts = array(result.postIds());
        assertThat(count("SELECT count(*) FROM ai.jobs WHERE kind = 'PREPARE' AND payload->>'post_id' = ANY(CAST(? AS text[]))",
                posts)).isEqualTo(12);

        // 워커가 PREPARE 를 끝낸 것처럼 둔다. 그 뒤 게이트(JuryScheduler 가 도는 것과 같은 호출)가 SENTENCE 를 넣는다
        jdbc.update("UPDATE ai.jobs SET status = 'SUCCEEDED' WHERE kind = 'PREPARE' AND payload->>'post_id' = ANY(CAST(? AS text[]))",
                posts);
        List<PendingVerdict> verdicts = jdbc.query("""
                        SELECT id, post_id, verdict_version FROM verdicts WHERE post_id::text = ANY(CAST(? AS text[]))""",
                (rs, i) -> new PendingVerdict(rs.getObject("id", UUID.class), rs.getObject("post_id", UUID.class),
                        rs.getInt("verdict_version")), posts);
        assertThat(verdicts).hasSize(10);
        verdicts.forEach(sentenceGate::tryInsert);

        String verdictIds = array(verdicts.stream().map(PendingVerdict::verdictId).toList());
        assertThat(count("SELECT count(*) FROM ai.jobs WHERE kind = 'SENTENCE' AND aggregate_id = ANY(CAST(? AS text[]))",
                verdictIds)).isEqualTo(10);
        assertThat(count("SELECT count(*) FROM verdicts WHERE id::text = ANY(CAST(? AS text[])) AND deadline_at IS NOT NULL",
                verdictIds)).isEqualTo(10);
        // 판결 문구는 시드가 쓰지 않는다
        assertThat(count("SELECT count(*) FROM verdict_texts WHERE verdict_id::text = ANY(CAST(? AS text[]))", verdictIds))
                .isZero();
    }

    @Test
    @DisplayName("10 §12·§16.3 출력 — 데모 C 사용자 id·방 id·스타벅스 post_id 2·verdict_id 2·시드 기준 시각")
    void logsAiSeedIdentifiers(CapturedOutput output) {
        List<UUID> users = users();
        SeedResult result = runner().seed(users);

        String postIds = result.starbucksPostIds().get(0) + "," + result.starbucksPostIds().get(1);
        String verdictIds = result.starbucksVerdictIds().get(0) + "," + result.starbucksVerdictIds().get(1);
        String now = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(result.seedNow());
        assertThat(output.getOut()).contains(
                "demo_user=" + users.getFirst(),
                "demo_room=" + result.demoRoomId(),
                "post_ids=" + postIds,
                "verdict_ids=" + verdictIds,
                "now=" + now,
                "--demo-user " + users.getFirst() + " --demo-room " + result.demoRoomId() + " --post-ids " + postIds
                        + " --verdict-ids " + verdictIds + " --now " + now);
        assertThat(now).endsWith("Z");
    }

    @Test
    @DisplayName("10 §12 intake BLOCKED 면 러너가 실패한다")
    void blockedFailsRunner() {
        DemoSeedRunner runner = runner(req -> result(req, Status.BLOCKED, "등록할 수 없습니다."));

        assertThatThrownBy(() -> runner.seed(users()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("label=스타벅스-1")
                .hasMessageContaining("BLOCKED");
    }

    @Test
    @DisplayName("10 §12 운영 DB 보호 — seed 프로필이 아니면 러너 빈이 없다")
    void runnerOnlyInSeedProfile() {
        assertThat(context.getBeansOfType(DemoSeedRunner.class)).isEmpty();
        assertThat(context.getBeansOfType(SeedQueries.class)).isEmpty();
    }

    @Test
    @DisplayName("10 §12 GEOJI_SEED_USER_IDS 는 서로 다른 UUID 4개")
    void parseUserIds() {
        List<UUID> ids = users();
        assertThat(DemoSeedRunner.parseUserIds(ids.stream().map(UUID::toString).collect(Collectors.joining(" , "))))
                .isEqualTo(ids);
        String a = ids.getFirst().toString();
        for (String bad : new String[] {"", null, a, a + "," + a + "," + a + "," + a, a + ",x,y,z",
                String.join(",", ids.stream().map(UUID::toString).toList()) + "," + UUID.randomUUID()}) {
            assertThatThrownBy(() -> DemoSeedRunner.parseUserIds(bad)).isInstanceOf(IllegalStateException.class)
                    .hasMessageNotContaining(a);
        }
    }
}
