package com.ttegeoji.backend.comment;

import com.ttegeoji.backend.internal.CommentSource;
import com.ttegeoji.backend.internal.dto.CommentSnapshot;
import com.ttegeoji.backend.support.PostgresContainerSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// MockMvc 는 같은 스레드라 @Transactional 테스트 행이 컨트롤러에서도 보인다(롤백). 백그라운드 스케줄러도 행을 보지 않는다
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CommentSnapshotSourceTest extends PostgresContainerSupport {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String LEASE = "now() + interval '30 seconds'";

    @Autowired
    private CommentSource source;
    @Autowired
    private PostCommentService service;
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    // 공개 체인의 issuer-uri 디코더가 밖으로 나가지 않게 막는다
    @MockitoBean
    private JwtDecoder jwtDecoder;

    private CommentFixtures f;
    private UUID author;
    private UUID member;
    private UUID room;
    private UUID post;
    private UUID comment;

    @BeforeEach
    void seed() {
        f = new CommentFixtures(jdbc);
        author = f.profile();
        member = f.profile();
        room = f.room(author);
        f.member(room, author);
        f.member(room, member);
        post = f.post(author);
        f.share(post, room);
        f.judge(post);
        comment = f.comment(post, room, member, "택시 세 번째는 선 넘었다");
    }

    @Test
    @DisplayName("10 §4.1 CommentSource 빈은 CommentSnapshotSource 하나(NoCommentSource 삭제)")
    void singleBean() {
        assertThat(source).isInstanceOf(CommentSnapshotSource.class);
    }

    @Test
    @DisplayName("10 §4.1 정상 스냅샷 8필드 — post_status JUDGED·created_at RFC3339 UTC·version 1")
    void normalSnapshot() {
        OffsetDateTime createdAt = jdbc.queryForObject("SELECT created_at FROM post_comments WHERE id = ?",
                OffsetDateTime.class, comment);

        CommentSnapshot snapshot = source.find(comment.toString()).orElseThrow();

        assertThat(snapshot.commentId()).isEqualTo(comment.toString());
        assertThat(snapshot.version()).isEqualTo(1);
        assertThat(snapshot.roomId()).isEqualTo(room.toString());
        assertThat(snapshot.postId()).isEqualTo(post.toString());
        assertThat(snapshot.postStatus()).isEqualTo("JUDGED");
        assertThat(snapshot.authorId()).isEqualTo(member.toString());
        assertThat(snapshot.content()).isEqualTo("택시 세 번째는 선 넘었다");
        assertThat(snapshot.createdAt()).endsWith("Z");
        assertThat(OffsetDateTime.parse(snapshot.createdAt())).isEqualTo(createdAt.withOffsetSameInstant(ZoneOffset.UTC));
        // snake 키 8개, 추가 키 없음(스키마 additionalProperties:false)
        Map<String, Object> json = JSON.readValue(JSON.writeValueAsString(snapshot), Map.class);
        assertThat(json).containsOnlyKeys("comment_id", "version", "room_id", "post_id", "post_status", "author_id",
                "content", "created_at");
    }

    @Test
    @DisplayName("10 §4.1 삭제 댓글 → empty")
    void deletedCommentEmpty() {
        f.deleteComment(comment);

        assertThat(source.find(comment.toString())).isEmpty();
    }

    @Test
    @DisplayName("10 §4.1 삭제 게시물 → empty")
    void deletedPostEmpty() {
        f.deletePost(post);

        assertThat(source.find(comment.toString())).isEmpty();
    }

    @Test
    @DisplayName("10 §4.1·§8 댓글 방 공유 철회 → empty")
    void revokedRoomEmpty() {
        f.revoke(post, room);

        assertThat(source.find(comment.toString())).isEmpty();
    }

    @Test
    @DisplayName("10 §4.1 게시물이 JUDGED 가 아니면(dismissed·투표 중) 새 post_status 값을 만들지 않고 empty(B-1)")
    void notJudgedEmpty() {
        UUID dismissedPost = f.post(author);
        f.share(dismissedPost, room);
        f.verdict(dismissedPost, "dismissed", "FINAL");
        UUID onDismissed = f.comment(dismissedPost, room, member, "각하");
        UUID votingPost = f.post(author);
        f.share(votingPost, room);
        UUID onVoting = f.comment(votingPost, room, member, "투표 중");

        assertThat(source.find(onDismissed.toString())).isEmpty();
        assertThat(source.find(onVoting.toString())).isEmpty();
    }

    @Test
    @DisplayName("10 §4.1 null·UUID 아닌 id·없는 id → 예외 없이 empty(500 아님)")
    void invalidIdEmpty() {
        assertThat(source.find(null)).isEmpty();
        assertThat(source.find("not-a-uuid")).isEmpty();
        assertThat(source.find("")).isEmpty();
        assertThat(source.find(UUID.randomUUID().toString())).isEmpty();
    }

    private UUID runningRetainJob(UUID commentId, UUID generation) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO ai.jobs (id, event_id, event_type, kind, dedupe_key, aggregate_id, aggregate_version,
                                     payload, status, priority, attempts, max_attempts, lease_until, owner_id,
                                     generation_id, trace_id)
                VALUES (?, gen_random_uuid(), 'comment.approved', 'RETAIN', ?, ?, 1, CAST(? AS jsonb), 'RUNNING', 10, 0,
                        5, %s, 'worker-1', ?, 'trace')
                """.formatted(LEASE),
                id, "test:" + id, commentId.toString(),
                "{\"event\": \"comment.approved\", \"verdict_id\": null, \"comment_id\": \"" + commentId
                        + "\", \"version\": 1}",
                generation);
        return id;
    }

    // AiJobControllerTest.withWorkerHeaders 와 같은 10 §4.7 헤더 5종
    private MockHttpServletRequestBuilder snapshotRequest(UUID jobId, UUID generation) {
        return get("/internal/v1/ai-jobs/{job_id}/snapshot", jobId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .header("X-Trace-Id", "trace-" + UUID.randomUUID())
                .header("X-Request-Id", UUID.randomUUID().toString())
                .header("X-Job-Id", jobId.toString())
                .header("X-Generation-Id", generation.toString());
    }

    @Test
    @DisplayName("10 §4.1·§4.7 RETAIN comment.approved snapshot → 200·comment 채워짐·room_snapshots 에 댓글 방. 댓글 삭제 뒤 404 NOT_FOUND")
    void snapshotEndpoint() throws Exception {
        UUID generation = UUID.randomUUID();
        UUID jobId = runningRetainJob(comment, generation);

        String body = mockMvc.perform(snapshotRequest(jobId, generation))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode n = JSON.readTree(body);
        assertThat(n.get("comment").get("comment_id").asString()).isEqualTo(comment.toString());
        assertThat(n.get("comment").get("post_status").asString()).isEqualTo("JUDGED");
        assertThat(n.get("comment").get("room_id").asString()).isEqualTo(room.toString());
        assertThat(n.get("comment").get("author_id").asString()).isEqualTo(member.toString());
        assertThat(n.get("jury").isNull()).isTrue();
        List<String> roomIds = new ArrayList<>();
        n.get("room_snapshots").forEach(r -> roomIds.add(r.get("room_id").asString()));
        assertThat(roomIds).contains(room.toString());

        service.delete(post, comment, member);

        mockMvc.perform(snapshotRequest(jobId, generation))
                .andExpect(status().isNotFound())
                .andExpect(content().json("{\"code\":\"NOT_FOUND\"}", JsonCompareMode.STRICT));
    }
}
