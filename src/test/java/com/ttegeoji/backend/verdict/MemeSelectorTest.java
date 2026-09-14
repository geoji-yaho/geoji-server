package com.ttegeoji.backend.verdict;

import com.ttegeoji.backend.support.PostgresContainerSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// 테스트마다 롤백한다. 다른 테스트가 커밋한 짤이 후보에 섞이지 않게 트랜잭션 안에서 기존 짤을 모두 끈다
@SpringBootTest
@Transactional
class MemeSelectorTest extends PostgresContainerSupport {

    @Autowired
    private MemeSelector selector;
    @Autowired
    private JdbcTemplate jdbc;

    private UUID authorId;

    private static final MemeSelector.Hints HINTS = new MemeSelector.Hints(
            "GUILTY_LIGHT", "CHEAPER_ALTERNATIVE", "DISAPPROVAL", List.of("택시", "늦잠"));

    @BeforeEach
    void setUp() {
        jdbc.update("UPDATE meme_images SET is_active = false");
        authorId = UUID.randomUUID();
        jdbc.update("INSERT INTO profiles (id, nickname, monthly_budget) VALUES (?, 'meme-test', 300000)", authorId);
    }

    @Test
    @DisplayName("10 §11 태그 일치 후보 중 최고점을 고른다(다른 태그·비활성은 제외)")
    void picksHighestScoreAmongTagMatches() {
        UUID best = insertMeme("GUILTY_LIGHT", "{CHEAPER_ALTERNATIVE}", "{DISAPPROVAL}", "{}", true);   // 3+2
        insertMeme("GUILTY_LIGHT", "{}", "{}", "{택시,늦잠}", true);                                       // 2
        insertMeme("NOT_GUILTY", "{CHEAPER_ALTERNATIVE}", "{DISAPPROVAL}", "{택시,늦잠}", true);           // 태그 불일치
        insertMeme("GUILTY_LIGHT", "{CHEAPER_ALTERNATIVE}", "{DISAPPROVAL}", "{택시,늦잠}", false);        // 비활성
        UUID verdict = insertVerdict(null);

        assertThat(selector.selectOnce(null, verdict, postOf(verdict), authorId, HINTS)).isEqualTo(best);
    }

    @Test
    @DisplayName("10 §11 이미 고정돼 있으면 후보와 무관하게 유지")
    void keepsAlreadyFixed() {
        insertMeme("GUILTY_LIGHT", "{CHEAPER_ALTERNATIVE}", "{DISAPPROVAL}", "{택시,늦잠}", true);
        UUID fixed = insertMeme("GUILTY_LIGHT", "{}", "{}", "{}", false);
        UUID verdict = insertVerdict(fixed);

        assertThat(selector.selectOnce(fixed, verdict, postOf(verdict), authorId, HINTS)).isEqualTo(fixed);
    }

    @Test
    @DisplayName("10 §11 같은 사용자 최근 노출 짤은 −5 라 다음 후보가 뽑힌다")
    void recentExposurePenalty() {
        UUID recent = insertMeme("GUILTY_LIGHT", "{CHEAPER_ALTERNATIVE}", "{DISAPPROVAL}", "{}", true); // 5 − 5 = 0
        UUID next = insertMeme("GUILTY_LIGHT", "{}", "{}", "{택시}", true);                              // 1
        insertVerdict(recent);
        UUID verdict = insertVerdict(null);

        assertThat(selector.selectOnce(null, verdict, postOf(verdict), authorId, HINTS)).isEqualTo(next);
    }

    @Test
    @DisplayName("10 §11 후보 0 → 기본 이미지 id 가 10 에 없어 null")
    void noCandidates() {
        UUID verdict = insertVerdict(null);

        assertThat(selector.selectOnce(null, verdict, postOf(verdict), authorId, HINTS)).isNull();
    }

    private UUID insertMeme(String tag, String strategies, String emotions, String keywords, boolean active) {
        return jdbc.queryForObject("""
                INSERT INTO meme_images (tag, strategies, emotions, keywords, image_url, is_active)
                VALUES (?, CAST(? AS text[]), CAST(? AS text[]), CAST(? AS text[]), 'https://cdn.example/m.png', ?)
                RETURNING id""", UUID.class, tag, strategies, emotions, keywords, active);
    }

    private UUID insertVerdict(UUID memeImageId) {
        UUID post = jdbc.queryForObject("""
                INSERT INTO posts (author_id, post_type, amount_krw, category, item, intake_status, intake_source,
                                   vote_deadline_at)
                VALUES (?, CAST('spent' AS post_type), 1000, '기타', '짤', 'PASS', 'AI', now()) RETURNING id""",
                UUID.class, authorId);
        return jdbc.queryForObject("""
                INSERT INTO verdicts (post_id, jury_result, policy_snapshot, confirmed_at, target_intensities,
                                      default_intensity, meme_image_id)
                VALUES (?, CAST('guilty' AS verdict), '{}'::jsonb, now(), '["mild"]'::jsonb, CAST('mild' AS spice_level), ?)
                RETURNING id""", UUID.class, post, memeImageId);
    }

    private UUID postOf(UUID verdictId) {
        return jdbc.queryForObject("SELECT post_id FROM verdicts WHERE id = ?", UUID.class, verdictId);
    }
}
