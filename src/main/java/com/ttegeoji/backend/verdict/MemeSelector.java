package com.ttegeoji.backend.verdict;

import com.ttegeoji.backend.domain.MemeImage;
import com.ttegeoji.backend.domain.enums.MemeTag;
import com.ttegeoji.backend.repository.MemeImageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * 10 §11 짤 선택. finalize 10단계에서 한 번만 고르고, 이미 고정돼 있으면 재생성(TEXT_RETRY)에서도 바꾸지 않는다.
 * 후보 0 이면 "결과별 기본 이미지"인데 그 id 가 10 에 없어 지금은 null 이다(코디네이터 회신 대기).
 */
@Component
@RequiredArgsConstructor
public class MemeSelector {

    static final int RECENT_LIMIT = 5;

    private final MemeImageRepository memeImageRepository;
    private final FinalizeQueries queries;

    public record Hints(String memeTag, String banterStrategy, String emotion, List<String> keywords) {
    }

    /**
     * @param currentMemeImageId verdicts.meme_image_id. 있으면 그대로 돌려준다
     * @return 고정할 짤 id. 후보가 없으면 null
     */
    public UUID selectOnce(UUID currentMemeImageId, UUID verdictId, UUID postId, UUID authorId, Hints hints) {
        if (currentMemeImageId != null) {
            return currentMemeImageId;
        }
        List<MemeScorer.Candidate> candidates = memeImageRepository
                .findByTagAndActiveTrue(MemeTag.valueOf(hints.memeTag())).stream()
                .map(MemeSelector::toCandidate)
                .toList();
        List<String> recent = queries.findRecentMemeImageIds(authorId, verdictId, RECENT_LIMIT);
        return MemeScorer.select(candidates, hints.memeTag(), hints.banterStrategy(), hints.emotion(),
                        hints.keywords(), recent, postId.toString())
                .map(UUID::fromString)
                .orElse(null);
    }

    private static MemeScorer.Candidate toCandidate(MemeImage image) {
        return new MemeScorer.Candidate(image.getId().toString(), image.getTag().name(), list(image.getStrategies()),
                list(image.getEmotions()), list(image.getKeywords()), Boolean.TRUE.equals(image.getActive()));
    }

    private static List<String> list(String[] values) {
        return values == null ? List.of() : Arrays.asList(values);
    }
}
