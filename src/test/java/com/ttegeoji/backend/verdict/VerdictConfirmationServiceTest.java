package com.ttegeoji.backend.verdict;

import com.ttegeoji.backend.domain.PostRoom;
import com.ttegeoji.backend.domain.Vote;
import com.ttegeoji.backend.domain.enums.VerdictType;
import com.ttegeoji.backend.jobs.JobKind;
import com.ttegeoji.backend.repository.PostRoomRepository;
import com.ttegeoji.backend.repository.VoteRepository;
import com.ttegeoji.backend.support.PostgresContainerSupport;
import com.ttegeoji.backend.util.Json;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 기본은 테스트마다 롤백한다(백그라운드 JuryScheduler 가 커밋 안 된 게시물을 보지 못한다).
 * 동시 도착·롤백·커밋 케이스는 실제 커밋이 필요해 NOT_SUPPORTED 로 돌리고 마감을 미래로 두어 스케줄러의 마감 스캔과 섞이지 않게 한다.
 */
@SpringBootTest
@Transactional
@ExtendWith(OutputCaptureExtension.class)
class VerdictConfirmationServiceTest extends PostgresContainerSupport {

    @Autowired
    private VerdictConfirmationService service;
    @Autowired
    private JuryQueries juryQueries;
    @Autowired
    private VoteRepository voteRepository;
    @Autowired
    private PostRoomRepository postRoomRepository;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;

    // 비유죄(notGuilty·agree·disagree)의 policy_snapshot — 최저 밴드(사용자 9/15)
    private static final Map<String, Object> LOWEST_BAND = Map.of(
            "version", "sentencing-band-v1",
            "allowed_sentences", List.of(Map.of("code", "probation", "rank", 1)),
            "fallback_sentence", "probation",
            "reason_required", true);

    @Test
    @DisplayName("9/16 방마다 따로 확정 — 한 방이 정족수를 채워도 다른 방은 투표 중")
    void confirmsPerRoom() {
        UUID author = profile();
        UUID mild = room("mild", "now() - interval '1 day'");
        UUID hell = room("hell", "now()");
        UUID m1 = profile();
        UUID m2 = profile();
        UUID h1 = profile();
        UUID h2 = profile();
        member(mild, author);
        member(hell, author);
        member(mild, m1);
        member(mild, m2);
        member(hell, h1);
        member(hell, h2);
        UUID post = post(author, "spent", "now() + interval '1 hour'", mild, hell);

        // mild 만 전원 투표 → mild 판결만 생긴다
        vote(post, m1, mild, VerdictType.notGuilty);
        assertThat(service.onVoteCast(post, mild)).isFalse();
        vote(post, m2, mild, VerdictType.notGuilty);
        assertThat(service.onVoteCast(post, mild)).isTrue();

        assertThat(verdictCount(post)).isEqualTo(1);
        Map<String, Object> mildVerdict = verdictRow(post, mild);
        assertThat(mildVerdict).containsEntry("jury_result", "notGuilty")
                .containsEntry("default_intensity", "mild")
                .containsEntry("applied_intensity", "mild");
        // 강도는 그 방 하나뿐이다
        assertThat(Json.read((String) mildVerdict.get("target_intensities"))).isEqualTo(List.of("mild"));

        // hell 은 아직 투표 중이고, mild 표는 hell 집계에 들어가지 않는다
        vote(post, h1, hell, VerdictType.guilty);
        assertThat(service.onVoteCast(post, hell)).isFalse();
        vote(post, h2, hell, VerdictType.guilty);
        assertThat(service.onVoteCast(post, hell)).isTrue();

        assertThat(verdictCount(post)).isEqualTo(2);
        Map<String, Object> hellVerdict = verdictRow(post, hell);
        assertThat(hellVerdict).containsEntry("jury_result", "guilty")
                .containsEntry("applied_intensity", "hell");
        assertThat(Json.read((String) hellVerdict.get("target_intensities"))).isEqualTo(List.of("hell"));

        // 같은 게시물인데 방마다 평결이 다르다
        assertThat(mildVerdict.get("id")).isNotEqualTo(hellVerdict.get("id"));
        assertThat(sentenceJobCount(post)).isEqualTo(2);
    }

    @Test
    @DisplayName("9/16 두 방에 다 있는 사람은 방마다 한 표씩 낸다")
    void sameVoterVotesInEachRoom() {
        UUID author = profile();
        UUID a = room("mild", "now()");
        UUID b = room("hell", "now()");
        UUID both = profile();
        member(a, author);
        member(b, author);
        member(a, both);
        member(b, both);
        UUID post = post(author, "spent", "now() + interval '1 hour'", a, b);

        vote(post, both, a, VerdictType.guilty);
        vote(post, both, b, VerdictType.notGuilty);

        assertThat(service.onVoteCast(post, a)).isTrue();
        assertThat(service.onVoteCast(post, b)).isTrue();

        assertThat(verdictRow(post, a)).containsEntry("jury_result", "guilty");
        assertThat(verdictRow(post, b)).containsEntry("jury_result", "notGuilty");
    }

    @Test
    @DisplayName("10 §3 마감 스캔 확정 — vote_deadline_at 지난 미확정 게시물")
    void deadlineScanConfirms() {
        UUID author = profile();
        UUID room = room("spicy", "now()");
        UUID m1 = profile();
        UUID m2 = profile();
        UUID m3 = profile();
        member(room, m1);
        member(room, m2);
        member(room, m3);
        UUID post = post(author, "spent", "now() - interval '1 minute'", room);
        vote(post, m1, room, VerdictType.guilty);
        vote(post, m2, room, VerdictType.guilty);
        assertThat(service.onVoteCast(post, room)).isFalse();

        assertThat(service.confirmDue(juryQueries.dbNow())).isGreaterThanOrEqualTo(1);

        Map<String, Object> v = verdictRow(post);
        assertThat(v).containsEntry("jury_result", "guilty").containsEntry("default_intensity", "spicy");
        @SuppressWarnings("unchecked")
        Map<String, Object> policy = (Map<String, Object>) Json.read((String) v.get("policy_snapshot"));
        assertThat(policy).containsEntry("fallback_sentence", "life");
        assertThat(sentenceJobCount(post)).isEqualTo(1);
    }

    @Test
    @DisplayName("10 §3 마감 전 게시물은 마감 스캔이 확정하지 않는다")
    void deadlineScanSkipsNotDue() {
        UUID author = profile();
        UUID room = room("mild", "now()");
        UUID m1 = profile();
        member(room, m1);
        member(room, profile());
        UUID post = post(author, "spent", "now() + interval '1 hour'", room);
        vote(post, m1, room, VerdictType.guilty);

        service.confirmDue(juryQueries.dbNow());

        assertThat(verdictCount(post)).isZero();
    }

    @Test
    @DisplayName("10 §3 dismissed(정족수 미달) → verdict 는 있고 SENTENCE job 0, 보류 목록에도 없다")
    void dismissedHasNoJob() {
        UUID author = profile();
        UUID room = room("mild", "now()");
        UUID m1 = profile();
        member(room, m1);
        member(room, profile());
        member(room, profile());
        UUID post = post(author, "spent", "now() - interval '1 minute'", room);
        vote(post, m1, room, VerdictType.guilty);

        service.confirmDue(juryQueries.dbNow());

        Map<String, Object> v = verdictRow(post);
        assertThat(v).containsEntry("jury_result", "dismissed").containsEntry("policy_is_null", true);
        assertThat(sentenceJobCount(post)).isZero();
        assertThat(juryQueries.pendingVerdicts()).noneMatch(p -> p.postId().equals(post));
    }

    @Test
    @DisplayName("10 §3 considering disagree → SENTENCE job 있음, policy_snapshot 최저 밴드")
    void disagreeHasJob() {
        UUID author = profile();
        UUID room = room("mild", "now()");
        UUID m1 = profile();
        UUID m2 = profile();
        member(room, m1);
        member(room, m2);
        UUID post = post(author, "considering", "now() + interval '1 hour'", room);
        vote(post, m1, room, VerdictType.disagree);
        vote(post, m2, room, VerdictType.disagree);

        assertThat(service.onVoteCast(post, room)).isTrue();

        Map<String, Object> v = verdictRow(post);
        assertThat(v).containsEntry("jury_result", "disagree").containsEntry("policy_is_null", false);
        assertThat(Json.read((String) v.get("policy_snapshot"))).isEqualTo(LOWEST_BAND);
        assertThat(sentenceJobCount(post)).isEqualTo(1);
    }

    @Test
    @DisplayName("10 §3 agree → SENTENCE job 있음, policy_snapshot 최저 밴드")
    void agreeHasLowestBandPolicy() {
        UUID author = profile();
        UUID room = room("mild", "now()");
        UUID m1 = profile();
        UUID m2 = profile();
        member(room, m1);
        member(room, m2);
        UUID post = post(author, "considering", "now() + interval '1 hour'", room);
        vote(post, m1, room, VerdictType.agree);
        vote(post, m2, room, VerdictType.agree);

        assertThat(service.onVoteCast(post, room)).isTrue();

        Map<String, Object> v = verdictRow(post);
        assertThat(v).containsEntry("jury_result", "agree");
        assertThat(Json.read((String) v.get("policy_snapshot"))).isEqualTo(LOWEST_BAND);
        assertThat(sentenceJobCount(post)).isEqualTo(1);
    }

    @Test
    @DisplayName("10 §3 notGuilty → SENTENCE job 있음(양형관만 건너뜀), policy_snapshot 최저 밴드")
    void notGuiltyHasJob() {
        UUID author = profile();
        UUID room = room("spicy", "now()");
        UUID m1 = profile();
        UUID m2 = profile();
        member(room, m1);
        member(room, m2);
        UUID post = post(author, "spent", "now() + interval '1 hour'", room);
        vote(post, m1, room, VerdictType.notGuilty);
        vote(post, m2, room, VerdictType.notGuilty);

        assertThat(service.onVoteCast(post, room)).isTrue();

        Map<String, Object> v = verdictRow(post);
        assertThat(v).containsEntry("jury_result", "notGuilty").containsEntry("policy_is_null", false);
        assertThat(Json.read((String) v.get("policy_snapshot"))).isEqualTo(LOWEST_BAND);
        assertThat(sentenceJobCount(post)).isEqualTo(1);
    }

    @Test
    @DisplayName("10 §3 허용 목록 빈 정책 → verdict 는 저장, SENTENCE job 0 + ERROR 로그, 게이트 보류 목록에도 없다")
    void emptyPolicyHasNoJob(CapturedOutput output) {
        Case c = guiltyAllVotedPost();
        UUID post = c.post();
        service.usePolicy(ratio -> new SentencingPolicy.Snapshot(SentencingPolicy.VERSION, List.of(), "probation", true));
        try {
            assertThat(service.onVoteCast(post, c.room())).isTrue();
        } finally {
            service.resetPolicy();
        }

        assertThat(verdictRow(post)).containsEntry("jury_result", "guilty");
        assertThat(sentenceJobCount(post)).isZero();
        assertThat(juryQueries.pendingVerdicts()).noneMatch(p -> p.postId().equals(post));
        assertThat(output).contains("ERROR").contains("정책 설정 오류").contains(post.toString());
    }

    @Test
    @DisplayName("10 §3 fallback ∉ 허용 목록 → SENTENCE job 0 + ERROR 로그")
    void fallbackOutsideListHasNoJob(CapturedOutput output) {
        Case c = guiltyAllVotedPost();
        UUID post = c.post();
        service.usePolicy(ratio -> new SentencingPolicy.Snapshot(SentencingPolicy.VERSION,
                List.of(new SentencingPolicy.AllowedSentence("probation", 1)), "life", true));
        try {
            assertThat(service.onVoteCast(post, c.room())).isTrue();
        } finally {
            service.resetPolicy();
        }

        assertThat(sentenceJobCount(post)).isZero();
        assertThat(output).contains("정책 설정 오류").contains(post.toString());
    }

    @Test
    @DisplayName("10 §3 이미 확정된 게시물 재호출 → no-op(verdict 1행·job 1개)")
    void alreadyConfirmedIsNoop() {
        Case c = guiltyAllVotedPost();
        UUID post = c.post();

        assertThat(service.onVoteCast(post, c.room())).isTrue();
        assertThat(service.onVoteCast(post, c.room())).isFalse();
        service.confirmDue(juryQueries.dbNow().plusDays(1));

        assertThat(verdictCount(post)).isEqualTo(1);
        assertThat(sentenceJobCount(post)).isEqualTo(1);
    }

    @Test
    @DisplayName("10 §3 삭제된 게시물은 전원 투표·마감 스캔 모두 확정하지 않는다")
    void deletedPostNotConfirmed() {
        Case c = guiltyAllVotedPost();
        UUID post = c.post();
        jdbc.update("UPDATE posts SET deleted_at = now(), vote_deadline_at = now() - interval '1 minute' WHERE id = ?",
                post);

        assertThat(service.onVoteCast(post, c.room())).isFalse();
        service.confirmDue(juryQueries.dbNow());

        assertThat(verdictCount(post)).isZero();
        assertThat(sentenceJobCount(post)).isZero();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("10 §13 마지막 표 두 개 동시 도착(스레드 2개) → verdict 1행, SENTENCE job 1개")
    void lastTwoVotesConcurrently() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        UUID[] ids = tx.execute(s -> {
            UUID author = profile();
            UUID room = room("mild", "now()");
            UUID m1 = profile();
            UUID m2 = profile();
            UUID m3 = profile();
            member(room, m1);
            member(room, m2);
            member(room, m3);
            UUID post = post(author, "spent", "now() + interval '1 hour'", room);
            vote(post, m1, room, VerdictType.guilty);
            return new UUID[]{post, room, m2, m3};
        });
        UUID post = ids[0];
        UUID room = ids[1];

        CyclicBarrier bothVoted = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> results = List.of(ids[2], ids[3]).stream()
                    .map(voter -> pool.submit(() -> tx.execute(s -> {
                        vote(post, voter, room, VerdictType.guilty);
                        // 두 표가 모두 INSERT 된 뒤(아직 커밋 전) 둘이 같이 확정을 시도한다
                        await(bothVoted);
                        return service.onVoteCast(post, room);
                    })))
                    .toList();
            int confirmed = 0;
            for (Future<Boolean> result : results) {
                if (Boolean.TRUE.equals(result.get(30, TimeUnit.SECONDS))) {
                    confirmed++;
                }
            }
            assertThat(confirmed).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }

        assertThat(verdictCount(post)).isEqualTo(1);
        assertThat(sentenceJobCount(post)).isEqualTo(1);
        assertThat(voteRepository.countByPostId(post)).isEqualTo(3);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("10 §3 확정 트랜잭션 롤백 → verdict 도 SENTENCE job 도 없다")
    void rollbackRemovesVerdictAndJob() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        Case c = tx.execute(s -> guiltyAllVotedPost());
        UUID post = c.post();

        tx.executeWithoutResult(s -> {
            assertThat(service.onVoteCast(post, c.room())).isTrue();
            assertThat(sentenceJobCount(post)).isEqualTo(1);
            s.setRollbackOnly();
        });

        assertThat(verdictCount(post)).isZero();
        assertThat(sentenceJobCount(post)).isZero();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("10 §13 평결 commit 직후 중단 — SENTENCE job 은 verdict 와 같이 커밋돼 QUEUED, deadline 일치")
    void jobCommittedWithVerdict() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        Case c = tx.execute(s -> guiltyAllVotedPost());
        UUID post = c.post();

        assertThat(service.onVoteCast(post, c.room())).isTrue();

        Map<String, Object> v = verdictRow(post);
        Map<String, Object> job = sentenceJob(post);
        assertThat(job).containsEntry("status", "QUEUED");
        assertThat(instant(v.get("deadline_at")))
                .isEqualTo(instant(job.get("deadline_at")));
    }

    // --- 픽스처 -------------------------------------------------------------------

    /** 재판 한 건 = (게시물, 방). 판결이 방마다 나므로 확정도 방을 지정해 부른다 */
    private record Case(UUID post, UUID room) {
    }

    /** 가능 인원 2명이 모두 guilty 를 낸 spent 게시물(마감 1시간 뒤). */
    private Case guiltyAllVotedPost() {
        UUID author = profile();
        UUID room = room("mild", "now()");
        UUID m1 = profile();
        UUID m2 = profile();
        member(room, author);
        member(room, m1);
        member(room, m2);
        UUID post = post(author, "spent", "now() + interval '1 hour'", room);
        vote(post, m1, room, VerdictType.guilty);
        vote(post, m2, room, VerdictType.guilty);
        return new Case(post, room);
    }

    private UUID profile() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO profiles (id, nickname, monthly_budget) VALUES (?, 'n', 0)", id);
        return id;
    }

    private UUID room(String spice, String createdAtExpr) {
        return jdbc.queryForObject("""
                INSERT INTO rooms (name, spice_level, vote_deadline_minutes, created_by, created_at)
                VALUES ('room', CAST(? AS spice_level), 30, gen_random_uuid(), %s) RETURNING id""".formatted(createdAtExpr),
                UUID.class, spice);
    }

    private void member(UUID room, UUID user) {
        jdbc.update("INSERT INTO room_members (room_id, user_id) VALUES (?, ?)", room, user);
    }

    private UUID post(UUID author, String postType, String deadlineExpr, UUID... rooms) {
        UUID post = jdbc.queryForObject("""
                INSERT INTO posts (author_id, post_type, amount_krw, category, item, intake_status, intake_source,
                                   vote_deadline_at)
                VALUES (?, CAST(? AS post_type), 5000, '카페/간식', '라떼', 'PASS', 'AI', %s) RETURNING id"""
                .formatted(deadlineExpr), UUID.class, author, postType);
        for (UUID room : rooms) {
            postRoomRepository.saveAndFlush(PostRoom.of(post, room));
        }
        return post;
    }

    // 투표 API 는 W4 몫이라 repository 로 직접 넣는다. JDBC 조회가 보도록 flush 한다
    private void vote(UUID post, UUID voter, UUID room, VerdictType verdict) {
        voteRepository.saveAndFlush(Vote.builder()
                .postId(post).voterId(voter).roomId(room).verdict(verdict).reason("이유")
                .build());
    }

    private int verdictCount(UUID post) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM verdicts WHERE post_id = ?", Integer.class, post);
        return count == null ? 0 : count;
    }

    private Map<String, Object> verdictRow(UUID post) {
        return jdbc.queryForMap(VERDICT_COLUMNS + " WHERE post_id = ?", post);
    }

    /** 방마다 판결이 따로라 방을 지정해 읽는다 */
    private Map<String, Object> verdictRow(UUID post, UUID room) {
        return jdbc.queryForMap(VERDICT_COLUMNS + " WHERE post_id = ? AND room_id = ?", post, room);
    }

    private static final String VERDICT_COLUMNS = """
            SELECT id, jury_result::text AS jury_result, verdict_version, sentence_status, text_status,
                   default_intensity::text AS default_intensity, applied_intensity::text AS applied_intensity,
                   target_intensities::text AS target_intensities, policy_snapshot::text AS policy_snapshot,
                   jsonb_typeof(policy_snapshot) = 'null' AS policy_is_null, confirmed_at, deadline_at
              FROM verdicts""";

    private int sentenceJobCount(UUID post) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM ai.jobs WHERE kind = 'SENTENCE' AND payload->>'post_id' = ?",
                Integer.class, post.toString());
        return count == null ? 0 : count;
    }

    private Map<String, Object> sentenceJob(UUID post) {
        return jdbc.queryForMap("""
                SELECT status, deadline_at, payload::text AS payload
                  FROM ai.jobs WHERE kind = 'SENTENCE' AND payload->>'post_id' = ?""", post.toString());
    }

    private OffsetDateTime dbNowPlus(int seconds) {
        return jdbc.queryForObject("SELECT now() + make_interval(secs => ?)", OffsetDateTime.class, seconds);
    }

    // queryForMap 은 timestamptz 를 java.sql.Timestamp 로 준다
    private static Instant instant(Object timestamp) {
        return timestamp instanceof OffsetDateTime odt ? odt.toInstant() : ((Timestamp) timestamp).toInstant();
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
