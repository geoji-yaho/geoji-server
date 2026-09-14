package com.ttegeoji.backend.verdict;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.zip.CRC32;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MemeScorerTest {

    private static final String TAG = "GUILTY_LIGHT";
    private static final String STRATEGY = "CHEAPER_ALTERNATIVE";
    private static final String EMOTION = "DISAPPROVAL";
    private static final String POST_ID = "3b1f0c2e-5a44-4d7e-9f10-2c8e6a7b9d01";

    private static MemeScorer.Candidate candidate(String id, String tag, List<String> strategies,
                                                  List<String> emotions, List<String> keywords, boolean active) {
        return new MemeScorer.Candidate(id, tag, strategies, emotions, keywords, active);
    }

    private static Optional<String> select(List<MemeScorer.Candidate> candidates, List<String> recent) {
        return MemeScorer.select(candidates, TAG, STRATEGY, EMOTION, List.of("택시", "늦잠"), recent, POST_ID);
    }

    private static long crc(String postId, String id) {
        CRC32 crc = new CRC32();
        crc.update((postId + id).getBytes(StandardCharsets.UTF_8));
        return crc.getValue();
    }

    @Test
    @DisplayName("10 §11 태그가 다른 후보는 점수가 높아도 제외")
    void tagMismatchExcluded() {
        List<MemeScorer.Candidate> candidates = List.of(
                candidate("other", "GUILTY_HEAVY", List.of(STRATEGY), List.of(EMOTION), List.of("택시", "늦잠"), true),
                candidate("plain", TAG, List.of(), List.of(), List.of(), true));

        assertThat(select(candidates, List.of())).contains("plain");
    }

    @Test
    @DisplayName("10 §11 inactive 후보는 제외")
    void inactiveExcluded() {
        List<MemeScorer.Candidate> candidates = List.of(
                candidate("off", TAG, List.of(STRATEGY), List.of(EMOTION), List.of("택시"), false),
                candidate("on", TAG, List.of(), List.of(), List.of(), true));

        assertThat(select(candidates, List.of())).contains("on");
    }

    @Test
    @DisplayName("10 §11 +3 전략 · +2 감정 · +1 키워드(중복 제거 교집합) 합산")
    void scoreSum() {
        List<MemeScorer.Candidate> candidates = List.of(
                candidate("strategy-only", TAG, List.of(STRATEGY), List.of(), List.of(), true),                  // 3
                candidate("emotion-keywords", TAG, List.of(), List.of(EMOTION), List.of("택시", "택시", "늦잠"), true), // 2+2=4
                candidate("emotion-keyword", TAG, List.of(), List.of(EMOTION), List.of("택시"), true),             // 3
                candidate("all", TAG, List.of(STRATEGY), List.of(EMOTION), List.of("택시"), true));                // 6

        assertThat(MemeScorer.score(candidates.get(0), STRATEGY, EMOTION, List.of("택시", "늦잠"), List.of())).isEqualTo(3);
        assertThat(MemeScorer.score(candidates.get(1), STRATEGY, EMOTION, List.of("택시", "늦잠", "늦잠"), List.of())).isEqualTo(4);
        assertThat(MemeScorer.score(candidates.get(3), STRATEGY, EMOTION, List.of("택시", "늦잠"), List.of())).isEqualTo(6);
        assertThat(select(candidates, List.of())).contains("all");
    }

    @Test
    @DisplayName("10 §11 최근 노출 −5 로 순위가 뒤집힌다")
    void recentPenaltyReversesRank() {
        List<MemeScorer.Candidate> candidates = List.of(
                candidate("best", TAG, List.of(STRATEGY), List.of(EMOTION), List.of("택시"), true), // 6 → 1
                candidate("second", TAG, List.of(STRATEGY), List.of(), List.of(), true));           // 3

        assertThat(select(candidates, List.of())).contains("best");
        assertThat(select(candidates, List.of("best"))).contains("second");
    }

    @Test
    @DisplayName("10 §11 동점이면 crc32(post_id + image_id) 가 작은 쪽, 몇 번 불러도 같다")
    void tieBrokenByCrc32() {
        List<MemeScorer.Candidate> candidates = List.of(
                candidate("meme-a", TAG, List.of(STRATEGY), List.of(), List.of(), true),
                candidate("meme-b", TAG, List.of(STRATEGY), List.of(), List.of(), true),
                candidate("meme-c", TAG, List.of(STRATEGY), List.of(), List.of(), true));

        String expected = "meme-a";
        for (String id : List.of("meme-b", "meme-c")) {
            if (crc(POST_ID, id) < crc(POST_ID, expected)) {
                expected = id;
            }
        }

        assertThat(select(candidates, List.of())).contains(expected);
        assertThat(select(candidates.reversed(), List.of())).contains(expected);
        assertThat(select(candidates, List.of())).isEqualTo(select(candidates, List.of()));
    }

    @Test
    @DisplayName("10 §11 crc32 입력은 post_id + image_id 의 UTF-8 — Python zlib 값과 같다")
    void crc32MatchesPython() {
        // cd "$GEOJIBANG_ROOT/geoji-agent" && uv run python -c "import zlib;print(zlib.crc32(('3b1f0c2e-5a44-4d7e-9f10-2c8e6a7b9d01'+'meme-a').encode('utf-8')))"
        assertThat(crc(POST_ID, "meme-a")).isEqualTo(2875496121L);
    }

    @Test
    @DisplayName("10 §11 전략·감정 힌트가 null 이어도 NPE 없이 키워드 점수만 센다")
    void nullHintsScoreZero() {
        MemeScorer.Candidate c = candidate("k", TAG, List.of(STRATEGY), List.of(EMOTION), List.of("택시"), true);

        assertThat(MemeScorer.score(c, null, null, List.of("택시"), List.of())).isEqualTo(1);
        assertThat(MemeScorer.select(List.of(c), TAG, null, null, null, null, POST_ID)).contains("k");
    }

    @Test
    @DisplayName("10 §11 후보가 없으면 empty")
    void noCandidates() {
        assertThat(select(List.of(), List.of())).isEmpty();
        assertThat(select(List.of(candidate("x", "NO_SPEND", null, null, null, true)), null)).isEmpty();
    }
}
