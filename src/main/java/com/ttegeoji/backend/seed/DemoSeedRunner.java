package com.ttegeoji.backend.seed;

import com.ttegeoji.backend.comment.PostCommentService;
import com.ttegeoji.backend.comment.dto.CreatePostCommentRequest;
import com.ttegeoji.backend.domain.enums.SubmissionStatus;
import com.ttegeoji.backend.seed.SeedData.CommentSeed;
import com.ttegeoji.backend.seed.SeedData.PostSeed;
import com.ttegeoji.backend.seed.SeedData.RoomSeed;
import com.ttegeoji.backend.seed.SeedData.VoteSeed;
import com.ttegeoji.backend.submission.SubmissionService;
import com.ttegeoji.backend.submission.dto.CompleteRequest;
import com.ttegeoji.backend.submission.dto.SubmissionResponse;
import com.ttegeoji.backend.submission.dto.SubmitRequest;
import com.ttegeoji.backend.verdictview.PostVoteService;
import com.ttegeoji.backend.verdictview.dto.PostVoteRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 10 §12 데모 시드. {@code --spring.profiles.active=seed} 로 띄울 때만 빈이 생긴다(기본·운영·test 프로필에는 없다).
 * 사용자는 기존 Supabase Auth 사용자 id 4개를 GEOJI_SEED_USER_IDS 로 받는다(첫째가 데모 C, 코디네이터 9/15).
 * 게시물은 SubmissionService(실제 intake·PREPARE job), 표는 PostVoteService(전원 투표 즉시 평결 확정 → D-24 게이트 → SENTENCE),
 * 댓글은 PostCommentService 를 거친다. 판결 문구는 워커와 finalize 가 만든다.
 * PREPARE 완료를 기다리지 않는다 — SENTENCE 게이트가 PREPARE 종료 또는 확정 + 30초에 넣는다(10 §3).
 * 멱등: 있으면 건너뛴다(backend role 에 DELETE 가 없다). 끝에 AI 시드(10 §16.3)에 넘길 id 를 INFO 로그로 남긴다.
 */
@Slf4j
@Component
@Profile("seed")
public class DemoSeedRunner implements ApplicationRunner {

    /** AI 시드 입력(10 §12·§16.3). postIds·verdictIds 는 스타벅스 두 건, 생성 순서. */
    record SeedResult(UUID demoUserId, UUID demoRoomId, List<UUID> starbucksPostIds, List<UUID> starbucksVerdictIds,
                      OffsetDateTime seedNow, List<UUID> roomIds, List<UUID> postIds) {
    }

    private final SeedQueries queries;
    private final SubmissionService submissionService;
    private final PostVoteService voteService;
    private final PostCommentService commentService;
    private final String userIds;

    DemoSeedRunner(SeedQueries queries, SubmissionService submissionService, PostVoteService voteService,
                   PostCommentService commentService, @Value("${geoji.seed.user-ids:}") String userIds) {
        this.queries = queries;
        this.submissionService = submissionService;
        this.voteService = voteService;
        this.commentService = commentService;
        this.userIds = userIds;
    }

    @Override
    public void run(ApplicationArguments args) {
        seed(parseUserIds(userIds));
    }

    /** 서로 다른 UUID 4개(쉼표 구분). 틀리면 시작을 거부한다. 입력값은 메시지에 싣지 않는다. */
    static List<UUID> parseUserIds(String raw) {
        List<UUID> ids = new ArrayList<>();
        if (raw != null && !raw.isBlank()) {
            for (String part : raw.split(",")) {
                try {
                    ids.add(UUID.fromString(part.strip()));
                } catch (IllegalArgumentException e) {
                    throw new IllegalStateException("GEOJI_SEED_USER_IDS 에 UUID 가 아닌 값이 있습니다.");
                }
            }
        }
        if (ids.size() != SeedData.USER_COUNT || new LinkedHashSet<>(ids).size() != SeedData.USER_COUNT) {
            throw new IllegalStateException("GEOJI_SEED_USER_IDS 는 서로 다른 UUID " + SeedData.USER_COUNT
                    + "개(쉼표 구분, 첫째가 데모 C)여야 합니다.");
        }
        return List.copyOf(ids);
    }

    SeedResult seed(List<UUID> users) {
        for (int i = 0; i < users.size(); i++) {
            queries.insertProfileIfAbsent(users.get(i), SeedData.NICKNAMES.get(i), SeedData.MONTHLY_BUDGET);
        }

        UUID owner = users.get(SeedData.DEMO_USER);
        List<UUID> rooms = new ArrayList<>();
        for (RoomSeed room : SeedData.ROOMS) {
            UUID roomId = queries.findRoom(owner, room.name())
                    .orElseGet(() -> queries.insertRoom(owner, room.name(), room.spiceLevel().name(),
                            SeedData.VOTE_DEADLINE_MINUTES, room.rules()));
            for (int member : room.members()) {
                queries.insertMemberIfAbsent(roomId, users.get(member));
            }
            rooms.add(roomId);
        }

        List<UUID> posts = new ArrayList<>();
        for (int i = 0; i < SeedData.POSTS.size(); i++) {
            PostSeed seed = SeedData.POSTS.get(i);
            UUID author = users.get(seed.author());
            UUID roomId = rooms.get(seed.room());
            UUID postId = findOrSubmit(seed, occurrence(i), author, roomId);
            vote(seed, postId, roomId, users);
            comment(seed, postId, roomId, users);
            posts.add(postId);
        }

        List<UUID> starbucksPosts = new ArrayList<>();
        List<UUID> starbucksVerdicts = new ArrayList<>();
        for (int i = 0; i < SeedData.POSTS.size(); i++) {
            PostSeed seed = SeedData.POSTS.get(i);
            if (seed.author() == SeedData.DEMO_USER && SeedData.STARBUCKS_ITEM.equals(seed.item())) {
                starbucksPosts.add(posts.get(i));
                starbucksVerdicts.add(queries.verdictId(posts.get(i)).orElseThrow(() ->
                        new IllegalStateException("10 §12 스타벅스 게시물에 평결이 없다 label=" + seed.label())));
            }
        }
        // 시드 기준 시각 = 시드 게시물 중 가장 이른 생성 시각. 재실행해도 같다
        OffsetDateTime seedNow = posts.stream().map(queries::postCreatedAt).min(Comparator.naturalOrder())
                .orElseThrow().withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS);

        SeedResult result = new SeedResult(owner, rooms.get(SeedData.DEMO_ROOM), List.copyOf(starbucksPosts),
                List.copyOf(starbucksVerdicts), seedNow, List.copyOf(rooms), List.copyOf(posts));
        logResult(result);
        return result;
    }

    // 내용이 같은 앞선 시드 수. 스타벅스 두 건을 생성 순서로 구분한다
    private static int occurrence(int index) {
        PostSeed seed = SeedData.POSTS.get(index);
        int count = 0;
        for (int i = 0; i < index; i++) {
            PostSeed other = SeedData.POSTS.get(i);
            if (other.author() == seed.author() && other.room() == seed.room() && other.postType() == seed.postType()
                    && other.amountKrw() == seed.amountKrw() && other.category().equals(seed.category())
                    && other.item().equals(seed.item()) && Objects.equals(other.reason(), seed.reason())) {
                count++;
            }
        }
        return count;
    }

    private UUID findOrSubmit(PostSeed seed, int occurrence, UUID author, UUID roomId) {
        List<UUID> existing = queries.findPosts(author, roomId, seed.postType().name(), seed.amountKrw(),
                seed.category(), seed.item(), seed.reason());
        if (existing.size() > occurrence) {
            return existing.get(occurrence);
        }
        List<UUID> roomIds = List.of(roomId);
        SubmissionResponse submitted = submissionService.submit(author, new SubmitRequest(seed.postType(),
                seed.amountKrw(), seed.category(), seed.item(), seed.reason(), roomIds));
        if (submitted.status() == SubmissionStatus.NEEDS_INPUT) {
            // 질문을 받아도 시드 값 그대로 등록한다(UNCLARIFIED)
            submitted = submissionService.complete(author, submitted.submissionId(), new CompleteRequest(
                    CompleteRequest.Action.PROCEED, submitted.revision(), seed.postType(), seed.amountKrw(),
                    seed.category(), seed.item(), seed.reason(), roomIds));
        }
        if (submitted.status() != SubmissionStatus.COMPLETED || submitted.postId() == null) {
            log.error("10 §12 시드 게시물 등록 실패 label={} status={}", seed.label(), submitted.status());
            throw new IllegalStateException("10 §12 시드 게시물 등록 실패 label=" + seed.label()
                    + " status=" + submitted.status());
        }
        return submitted.postId();
    }

    private void vote(PostSeed seed, UUID postId, UUID roomId, List<UUID> users) {
        for (VoteSeed vote : seed.votes()) {
            // 확정된 뒤에는 PostVoteService 가 409 를 낸다. 이미 낸 표도 건너뛴다
            if (queries.verdictId(postId).isPresent()) {
                break;
            }
            UUID voter = users.get(vote.voter());
            if (!queries.voteExists(postId, voter)) {
                voteService.cast(postId, voter, new PostVoteRequest(vote.verdict(), vote.reason(), roomId));
            }
        }
        String result = queries.juryResult(postId).orElse(null);
        if (seed.expected() != null && !seed.expected().equals(result)) {
            throw new IllegalStateException("10 §12 시드 평결이 표와 다르다 label=" + seed.label()
                    + " expected=" + seed.expected() + " actual=" + result);
        }
        if (seed.expected() == null && result != null) {
            // 투표 중 글은 마감(30분)이 지나 재실행하면 각하로 확정돼 있을 수 있다
            log.warn("10 §12 투표 중이어야 할 시드 게시물이 이미 확정됨 label={} result={}", seed.label(), result);
        }
    }

    private void comment(PostSeed seed, UUID postId, UUID roomId, List<UUID> users) {
        if (seed.expected() == null) {
            return;
        }
        for (CommentSeed comment : seed.comments()) {
            UUID user = users.get(comment.user());
            if (!queries.commentExists(postId, user, comment.content())) {
                commentService.create(postId, user, new CreatePostCommentRequest(roomId.toString(), comment.content()));
            }
        }
    }

    private static void logResult(SeedResult result) {
        String postIds = join(result.starbucksPostIds());
        String verdictIds = join(result.starbucksVerdictIds());
        String now = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(result.seedNow());
        log.info("10 §12 데모 C 시드 식별자 demo_user={} demo_room={} post_ids={} verdict_ids={} now={}",
                result.demoUserId(), result.demoRoomId(), postIds, verdictIds, now);
        log.info("10 §16.3 AI 시드: uv run scripts/seed_agent_db.py --demo-user {} --demo-room {} --post-ids {}"
                + " --verdict-ids {} --now {}", result.demoUserId(), result.demoRoomId(), postIds, verdictIds, now);
    }

    private static String join(List<UUID> ids) {
        return ids.stream().map(UUID::toString).collect(Collectors.joining(","));
    }

    // 테스트가 시드 방 이름 목록을 본다
    static Set<String> roomNames() {
        return SeedData.ROOMS.stream().map(RoomSeed::name).collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
