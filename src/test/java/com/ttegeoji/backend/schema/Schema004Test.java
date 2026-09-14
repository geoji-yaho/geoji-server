package com.ttegeoji.backend.schema;

import com.ttegeoji.backend.domain.MemeImage;
import com.ttegeoji.backend.domain.Post;
import com.ttegeoji.backend.domain.PostRoom;
import com.ttegeoji.backend.domain.PrivacyInvalidation;
import com.ttegeoji.backend.domain.Submission;
import com.ttegeoji.backend.domain.Verdict;
import com.ttegeoji.backend.domain.VerdictText;
import com.ttegeoji.backend.domain.Vote;
import com.ttegeoji.backend.domain.enums.*;
import com.ttegeoji.backend.repository.*;
import com.ttegeoji.backend.support.PostgresContainerSupport;
import jakarta.persistence.EntityManager;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

// 테스트마다 롤백한다. 제약 위반은 각 테스트의 마지막 문장이다(실패한 트랜잭션에서 더 쓰지 않는다)
@SpringBootTest
@Transactional
class Schema004Test extends PostgresContainerSupport {

    private static final String NOT_NULL = "23502";
    private static final String UNIQUE = "23505";
    private static final String CHECK = "23514";

    private static final List<String> CATEGORIES = List.of(
            "식비", "배달", "카페/간식", "교통/택시", "쇼핑/패션", "뷰티", "취미/여가", "술/유흥", "구독", "생활", "기타");

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private PostRepository postRepository;
    @Autowired
    private PostRoomRepository postRoomRepository;
    @Autowired
    private VoteRepository voteRepository;
    @Autowired
    private VerdictRepository verdictRepository;
    @Autowired
    private VerdictTextRepository verdictTextRepository;
    @Autowired
    private SubmissionRepository submissionRepository;
    @Autowired
    private MemeImageRepository memeImageRepository;

    @Test
    @DisplayName("10 §2 004 적용 뒤 ddl-auto: validate 로 컨텍스트가 뜨고 새 엔티티가 등록된다")
    void contextStartsWithValidate() {
        assertThat(entityManager.getMetamodel().getEntities())
                .extracting(e -> e.getJavaType().getSimpleName())
                .contains("Post", "PostRoom", "Vote", "Verdict", "VerdictText", "Submission", "MemeImage",
                        "PrivacyInvalidation");
        assertThat(jdbc.queryForObject("SELECT to_regclass('ai.privacy_epochs')::text", String.class))
                .isEqualTo("ai.privacy_epochs");
    }

    @Test
    @DisplayName("10 §2 엔티티 8종이 저장·재조회된다(native enum·jsonb·text[] 매핑)")
    void entitiesRoundTrip() {
        UUID author = insertProfile();
        UUID voter = insertProfile();
        UUID room = insertRoom(author);

        Submission submission = submissionRepository.save(Submission.builder()
                .actorId(author).payloadHash("h").expiresAt(OffsetDateTime.now().plusMinutes(10))
                .intakeResult("{\"status\":\"PASS\"}").build());
        Post post = postRepository.save(Post.builder()
                .authorId(author).postType(PostType.considering).amountKrw(5500).category("카페/간식")
                .item("스타벅스 라떼").reason("졸려서").intakeStatus(IntakeStatus.PASS).intakeSource(IntakeSource.AI)
                .submissionId(submission.getId()).voteDeadlineAt(OffsetDateTime.now().plusMinutes(30)).build());
        postRoomRepository.save(PostRoom.of(post.getId(), room));
        voteRepository.save(Vote.builder().postId(post.getId()).voterId(voter).roomId(room)
                .verdict(VerdictType.disagree).reason("참아").build());
        MemeImage meme = memeImageRepository.save(MemeImage.builder().tag(MemeTag.REJECTED)
                .strategies(new String[]{"IRONY"}).emotions(new String[]{"SMUG", "PITY"})
                .keywords(new String[]{"커피"}).imageUrl("https://cdn.example/1.png").build());
        Verdict verdict = verdictRepository.save(Verdict.builder().postId(post.getId())
                .juryResult(VerdictType.disagree).policySnapshot("{\"allowed_sentences\":[]}")
                .confirmedAt(OffsetDateTime.now()).sentence(Sentence.oneDay).sentenceSource(ContentSource.RULE)
                .appliedIntensity(SpiceLevel.hell).memeImageId(meme.getId())
                .targetIntensities("[\"mild\",\"hell\"]").defaultIntensity(SpiceLevel.mild).build());
        verdictTextRepository.save(VerdictText.builder().verdictId(verdict.getId()).intensity(SpiceLevel.hell)
                .headline("기각").statement("[{\"text\":\"지갑을 닫으십시오.\",\"kind\":\"VERDICT\",\"evidence_labels\":[]}]")
                .source(ContentSource.TEMPLATE).textVersion(1L).build());
        // 무효화 기록 repository 는 W3 feat-privacy 몫이라 EntityManager 로 본다
        PrivacyInvalidation invalidation = PrivacyInvalidation.builder()
                .scopeKey("post:" + post.getId()).sourceType("POST").sourceId(post.getId().toString()).build();
        entityManager.persist(invalidation);
        entityManager.flush();
        entityManager.clear();

        Post loadedPost = postRepository.findBySubmissionId(submission.getId()).orElseThrow();
        assertThat(loadedPost.getPostType()).isEqualTo(PostType.considering);
        assertThat(loadedPost.getVersion()).isEqualTo(1);
        assertThat(loadedPost.getCreatedAt()).isNotNull();
        assertThat(postRoomRepository.findById_PostId(post.getId())).hasSize(1);
        assertThat(voteRepository.countByPostIdAndVerdict(post.getId(), VerdictType.disagree)).isEqualTo(1);

        Verdict loaded = verdictRepository.findByPostIdForUpdate(post.getId()).orElseThrow();
        assertThat(loaded.getVerdictVersion()).isEqualTo(1);
        assertThat(loaded.getSentenceStatus()).isEqualTo(SentenceStatus.PENDING);
        assertThat(loaded.getTextStatus()).isEqualTo(TextStatus.PENDING);
        assertThat(loaded.getTextVersion()).isZero();
        assertThat(loaded.getSentence()).isEqualTo(Sentence.oneDay);
        assertThat(loaded.getDefaultIntensity()).isEqualTo(SpiceLevel.mild);
        assertThat(verdictRepository.findByIdForUpdate(verdict.getId())).isPresent();
        assertThat(verdictTextRepository.findByVerdictIdAndIntensity(verdict.getId(), SpiceLevel.hell)).isPresent();
        assertThat(memeImageRepository.findByTagAndActiveTrue(MemeTag.REJECTED))
                .singleElement().satisfies(m -> assertThat(m.getEmotions()).containsExactly("SMUG", "PITY"));
        assertThat(submissionRepository.findByIdAndActorId(submission.getId(), author)).isPresent();
        assertThat(submissionRepository.findByIdAndActorId(submission.getId(), voter)).isEmpty();
        PrivacyInvalidation loadedInvalidation = entityManager.find(PrivacyInvalidation.class, invalidation.getId());
        assertThat(loadedInvalidation.getStatus()).isEqualTo(PrivacyInvalidation.Status.PENDING);
        assertThat(loadedInvalidation.getAttempts()).isZero();
        assertThat(loadedInvalidation.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("10 §2 posts.item NULL 거부")
    void postItemNullRejected() {
        UUID author = insertProfile();
        assertSqlState(() -> insertPost(author, null, "식비"), NOT_NULL);
    }

    @Test
    @DisplayName("10 §2 posts.item 30자는 받고 31자는 거부")
    void postItem31Rejected() {
        UUID author = insertProfile();
        insertPost(author, "가".repeat(30), "식비");
        assertSqlState(() -> insertPost(author, "가".repeat(31), "식비"), CHECK);
    }

    @Test
    @DisplayName("10 §15.2 category 11종은 받고 그 밖은 거부")
    void categoryOutsideRejected() {
        UUID author = insertProfile();
        CATEGORIES.forEach(category -> insertPost(author, "item", category));
        assertSqlState(() -> insertPost(author, "item", "카페"), CHECK);
    }

    @Test
    @DisplayName("10 §2 verdict_texts (verdict_id, intensity) 중복 거부")
    void verdictTextDuplicateRejected() {
        UUID verdict = insertVerdict(insertPost(insertProfile(), "item", "식비"));
        insertVerdictText(verdict, "spicy");
        insertVerdictText(verdict, "mild");
        assertSqlState(() -> insertVerdictText(verdict, "spicy"), UNIQUE);
    }

    @Test
    @DisplayName("10 §2 verdicts.post_id 중복 거부")
    void verdictPostIdDuplicateRejected() {
        UUID post = insertPost(insertProfile(), "item", "식비");
        insertVerdict(post);
        assertSqlState(() -> insertVerdict(post), UNIQUE);
    }

    @Test
    @DisplayName("9/14 votes (post_id, voter_id) 중복 거부 — 여러 방에 공유돼도 1표")
    void voteDuplicateRejected() {
        UUID author = insertProfile();
        UUID voter = insertProfile();
        UUID roomA = insertRoom(author);
        UUID roomB = insertRoom(author);
        UUID post = insertPost(author, "item", "식비");
        insertVote(post, voter, roomA, "guilty", "사치");
        assertSqlState(() -> insertVote(post, voter, roomB, "notGuilty", "필요"), UNIQUE);
    }

    @Test
    @DisplayName("9/14 votes.reason 빈 문자열 거부(1~500자)")
    void voteEmptyReasonRejected() {
        UUID author = insertProfile();
        UUID post = insertPost(author, "item", "식비");
        assertSqlState(() -> insertVote(post, insertProfile(), insertRoom(author), ""), CHECK);
    }

    @Test
    @DisplayName("10 §2 submissions UNIQUE(actor_id, id)")
    void submissionActorIdUnique() {
        String definition = jdbc.queryForObject("""
                SELECT string_agg(pg_get_constraintdef(oid), ',') FROM pg_constraint
                WHERE conrelid = 'submissions'::regclass AND contype = 'u'""", String.class);
        // id 가 PK 라 중복 INSERT 는 PK 에 먼저 걸린다. 복합 UNIQUE 는 제약 정의로 본다
        assertThat(definition).contains("UNIQUE (actor_id, id)");
    }

    @Test
    @DisplayName("9/14 004 적용 전부터 있던 rooms 행의 rule_version 은 1")
    void existingRoomRuleVersionDefaultsToOne() {
        // 004 가 만든 컬럼 정의 자체를 본다. ADD COLUMN ... NOT NULL DEFAULT 1 은 기존 행을 1 로 채운다
        assertThat(jdbc.queryForMap("""
                SELECT data_type, is_nullable, column_default FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'rooms' AND column_name = 'rule_version'"""))
                .containsEntry("data_type", "integer")
                .containsEntry("is_nullable", "NO")
                .containsEntry("column_default", "1");

        UUID room = insertRoom(insertProfile());
        assertThat(jdbc.queryForObject("SELECT rule_version FROM rooms WHERE id = ?", Integer.class, room))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("9/14 verdicts.last_failed_generation_id·last_failed_code 는 NULL 허용")
    void lastFailedColumnsNullable() {
        UUID verdict = insertVerdict(insertPost(insertProfile(), "item", "식비"));
        assertThat(jdbc.queryForMap(
                "SELECT last_failed_generation_id, last_failed_code FROM verdicts WHERE id = ?", verdict))
                .containsEntry("last_failed_generation_id", null)
                .containsEntry("last_failed_code", null);

        UUID generation = UUID.randomUUID();
        jdbc.update("UPDATE verdicts SET last_failed_generation_id = ?, last_failed_code = 'DEADLINE_EXCEEDED' WHERE id = ?",
                generation, verdict);
        assertThat(jdbc.queryForObject("SELECT last_failed_generation_id FROM verdicts WHERE id = ?", UUID.class, verdict))
                .isEqualTo(generation);
    }

    @Test
    @DisplayName("10 §8 privacy_invalidations.status 는 PENDING·DONE·FAILED 밖을 거부")
    void privacyInvalidationStatusRejected() {
        jdbc.update("INSERT INTO privacy_invalidations (scope_key, source_type, source_id, status) VALUES ('post:1', 'POST', '1', 'DONE')");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM privacy_invalidations WHERE scope_key = 'post:1' AND status = 'DONE'", String.class))
                .isEqualTo("DONE");
        assertSqlState(() -> jdbc.update(
                "INSERT INTO privacy_invalidations (scope_key, source_type, source_id, status) VALUES ('post:2', 'POST', '2', 'RUNNING')"),
                CHECK);
    }

    private void assertSqlState(ThrowingCallable call, String sqlState) {
        Throwable thrown = catchThrowable(call);
        assertThat(thrown).as("제약 위반이 나야 한다").isNotNull();
        Throwable cause = NestedExceptionUtils.getMostSpecificCause(thrown);
        assertThat(cause).isInstanceOf(SQLException.class);
        assertThat(((SQLException) cause).getSQLState()).isEqualTo(sqlState);
    }

    private UUID insertProfile() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO profiles (id, nickname, monthly_budget) VALUES (?, 'n', 0)", id);
        return id;
    }

    private UUID insertRoom(UUID createdBy) {
        return jdbc.queryForObject("""
                INSERT INTO rooms (name, spice_level, vote_deadline_minutes, created_by)
                VALUES ('room', 'mild', 30, ?) RETURNING id""", UUID.class, createdBy);
    }

    private UUID insertPost(UUID author, String item, String category) {
        return jdbc.queryForObject("""
                INSERT INTO posts (author_id, post_type, amount_krw, category, item, intake_status, intake_source, vote_deadline_at)
                VALUES (?, 'spent', 5000, ?, ?, 'PASS', 'AI', now() + interval '30 minutes') RETURNING id""",
                UUID.class, author, category, item);
    }

    private UUID insertVerdict(UUID post) {
        return jdbc.queryForObject("""
                INSERT INTO verdicts (post_id, jury_result, policy_snapshot, confirmed_at, target_intensities, default_intensity)
                VALUES (?, 'guilty', '{}'::jsonb, now(), '["mild"]'::jsonb, 'mild') RETURNING id""", UUID.class, post);
    }

    private void insertVerdictText(UUID verdict, String intensity) {
        jdbc.update("""
                INSERT INTO verdict_texts (verdict_id, intensity, headline, statement, source, text_version)
                VALUES (?, CAST(? AS spice_level), '유죄', '[]'::jsonb, 'TEMPLATE', 1)""", verdict, intensity);
    }

    private void insertVote(UUID post, UUID voter, UUID room, String reason) {
        insertVote(post, voter, room, "guilty", reason);
    }

    private void insertVote(UUID post, UUID voter, UUID room, String verdict, String reason) {
        jdbc.update("""
                INSERT INTO votes (post_id, voter_id, room_id, verdict, reason)
                VALUES (?, ?, ?, CAST(? AS verdict), ?)""", post, voter, room, verdict, reason);
    }
}
