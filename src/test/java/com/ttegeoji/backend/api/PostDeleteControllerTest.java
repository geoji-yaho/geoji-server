package com.ttegeoji.backend.api;

import com.ttegeoji.backend.api.PostVerdictControllerTest.Fixtures;
import com.ttegeoji.backend.support.PostgresContainerSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static com.ttegeoji.backend.api.PostVerdictControllerTest.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 커밋되는 컨테이너 DB 를 공유하므로 테스트마다 새 UUID 로 격리한다
@SpringBootTest
@AutoConfigureMockMvc
class PostDeleteControllerTest extends PostgresContainerSupport {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private Fixtures f;
    private UUID author;
    private UUID post;

    @BeforeEach
    void seed() {
        f = new Fixtures(jdbc);
        author = f.profile();
        UUID room = f.room(author, "mild");
        f.member(room, author);
        post = f.post(author, "spent", "now() + interval '1 hour'");
        f.share(post, room);
    }

    private String url(UUID postId) {
        return "/api/posts/" + postId;
    }

    private boolean deleted(UUID postId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT deleted_at IS NOT NULL FROM posts WHERE id = ?", Boolean.class, postId));
    }

    @Test
    @DisplayName("10 §8 작성자 삭제 → 204, deleted_at 표시")
    void authorDeletes() throws Exception {
        mockMvc.perform(delete(url(post)).with(as(author)))
                .andExpect(status().isNoContent());
        assertThat(deleted(post)).isTrue();
    }

    @Test
    @DisplayName("10 §8 이미 삭제된 게시물을 작성자가 다시 삭제 → 204")
    void redeleteIs204() throws Exception {
        mockMvc.perform(delete(url(post)).with(as(author))).andExpect(status().isNoContent());

        mockMvc.perform(delete(url(post)).with(as(author)))
                .andExpect(status().isNoContent());
        assertThat(deleted(post)).isTrue();
    }

    @Test
    @DisplayName("10 §8 작성자가 아닌 사용자 → 403 {message}, 삭제되지 않음")
    void strangerIs403() throws Exception {
        UUID stranger = f.profile();

        mockMvc.perform(delete(url(post)).with(as(stranger)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").exists());
        assertThat(deleted(post)).isFalse();
    }

    @Test
    @DisplayName("10 §8 없는 게시물 → 404 {message}")
    void unknownPostIs404() throws Exception {
        mockMvc.perform(delete(url(UUID.randomUUID())).with(as(author)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    @DisplayName("10 §8 D-26 삭제 → 그 게시물의 진행 중 PREPARE job CANCELLED")
    void deleteCancelsActiveJobs() throws Exception {
        UUID prepare = f.job("PREPARE", "QUEUED", "{\"post_id\": \"" + post + "\"}");

        mockMvc.perform(delete(url(post)).with(as(author)))
                .andExpect(status().isNoContent());

        assertThat(f.jobStatus(prepare)).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("JWT 없음 → 401, 삭제되지 않음")
    void missingJwtIs401() throws Exception {
        mockMvc.perform(delete(url(post)))
                .andExpect(status().isUnauthorized());
        assertThat(deleted(post)).isFalse();
    }
}
