package com.ttegeoji.backend.verdict;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.zip.CRC32;

/**
 * 10 §11 짤 선택 점수. 같은 입력이면 늘 같은 짤이 나와야 재시도·폴링 사이에 짤이 바뀌지 않는다.
 */
public final class MemeScorer {

    // 입력 검증은 finalize 스키마 검증 몫이라 여기서는 목록만 둔다
    public static final Set<String> EMOTIONS = Set.of(
            "DISAPPROVAL", "ABSURD_SERIOUSNESS", "SMUG", "PITY", "CELEBRATION", "RESIGNATION");

    public record Candidate(String id, String tag, List<String> strategies, List<String> emotions,
                            List<String> keywords, boolean active) {
    }

    private MemeScorer() {
    }

    public static Optional<String> select(List<Candidate> candidates, String memeTag, String banterStrategy,
                                         String emotion, List<String> hintKeywords,
                                         Collection<String> recentImageIds, String postId) {
        if (candidates == null) {
            return Optional.empty();
        }
        Collection<String> recent = recentImageIds == null ? List.of() : recentImageIds;
        Comparator<Candidate> order = Comparator
                .comparingInt((Candidate c) -> -score(c, banterStrategy, emotion, hintKeywords, recent))
                .thenComparingLong(c -> crc32(postId, c.id()))
                .thenComparing(Candidate::id);

        return candidates.stream()
                .filter(c -> c.active() && c.tag() != null && c.tag().equals(memeTag))
                .min(order)
                .map(Candidate::id);
    }

    static int score(Candidate candidate, String banterStrategy, String emotion, List<String> hintKeywords,
                     Collection<String> recentImageIds) {
        int score = 0;
        // List.of(...).contains(null) 은 NPE 라 null 힌트는 먼저 거른다
        if (banterStrategy != null && orEmpty(candidate.strategies()).contains(banterStrategy)) {
            score += 3;
        }
        if (emotion != null && orEmpty(candidate.emotions()).contains(emotion)) {
            score += 2;
        }
        Set<String> shared = new HashSet<>(orEmpty(candidate.keywords()));
        shared.retainAll(new HashSet<>(orEmpty(hintKeywords)));
        score += shared.size();
        if (recentImageIds != null && recentImageIds.contains(candidate.id())) {
            score -= 5;
        }
        return score;
    }

    private static long crc32(String postId, String imageId) {
        CRC32 crc = new CRC32();
        crc.update((postId + imageId).getBytes(StandardCharsets.UTF_8));
        return crc.getValue();
    }

    private static List<String> orEmpty(List<String> values) {
        return values == null ? List.of() : values;
    }
}
