package com.ttegeoji.backend.media;

import com.ttegeoji.backend.domain.MemeImage;
import com.ttegeoji.backend.domain.enums.MemeTag;
import com.ttegeoji.backend.repository.MemeImageRepository;
import com.ttegeoji.backend.verdict.MemeScorer;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 관리자 짤 업로드(10 §16.5). 파일을 저장소에 올리고 meme_images 에 후보로 넣는다.
 *
 * <p>모델을 부르지 않는다. OpenAI·xAI 키가 없어도 동작해야 한다는 것이 이 절의 전제다.
 *
 * <p>계약은 올린 뒤 검수·활성화를 따로 하게 되어 있지만, 마감 일정상 검수 단계 없이
 * 바로 후보로 넣는다(9/19 사용자 결정). 잘못 올렸으면 {@code is_active} 를 내리면 된다.
 */
@Service
@RequiredArgsConstructor
public class MemeUploadService {

    private static final Set<String> STRATEGIES = Set.of("CHEAPER_ALTERNATIVE", "FREE_ALTERNATIVE",
            "DIY_REPLACEMENT", "PREMISE_REJECTION", "EXCUSE_STRIPPING", "NECESSITY_APPROVAL",
            "REPEAT_OFFENSE", "ROOM_RULE_CALLBACK");
    private static final int MAX_KEYWORDS = 16;
    private static final int MAX_KEYWORD_LENGTH = 40;

    private final MediaProperties properties;
    private final MemeImageRepository repository;
    private final Optional<MemeStorage> storage;

    public record Uploaded(UUID id, String tag, String imageUrl, String assetKey, boolean active,
                           boolean alreadyExisted) {
    }

    /**
     * @param actorId 업로드하는 사람. allowlist 에 없으면 403
     * @throws MediaRejection 권한·설정·검증 문제
     */
    @Transactional
    public Uploaded upload(UUID actorId, byte[] bytes, String tag, String strategiesCsv,
                           String emotionsCsv, String keywordsCsv) {
        if (!properties.isAdmin(actorId)) {
            throw new MediaRejection(HttpStatus.FORBIDDEN, "짤을 올릴 권한이 없습니다.");
        }
        if (storage.isEmpty() || !properties.configured()) {
            throw new MediaRejection(HttpStatus.SERVICE_UNAVAILABLE, "이미지 저장소가 설정되지 않았습니다.");
        }

        MemeTag memeTag = parseTag(tag);
        String[] strategies = vocabulary(strategiesCsv, STRATEGIES, "strategies");
        String[] emotions = vocabulary(emotionsCsv, MemeScorer.EMOTIONS, "emotions");
        String[] keywords = keywords(keywordsCsv);

        UploadedImage image = UploadedImage.of(bytes);
        String assetKey = image.sha256();

        // 같은 파일을 두 번 올리면 새 행을 만들지 않는다. 판결이 같은 짤을 중복 후보로 보지 않게
        Optional<MemeImage> existing = repository.findByAssetKey(assetKey);
        if (existing.isPresent()) {
            MemeImage row = existing.get();
            return new Uploaded(row.getId(), row.getTag().name(), row.getImageUrl(), assetKey,
                    Boolean.TRUE.equals(row.getActive()), true);
        }

        String key = image.storageKey(properties.keyPrefix());
        String url = storage.get().put(key, image.bytes(), image.contentType());

        MemeImage saved = repository.save(MemeImage.builder()
                .tag(memeTag)
                .strategies(strategies)
                .emotions(emotions)
                .keywords(keywords)
                .imageUrl(url)
                .assetKey(assetKey)
                .active(true)
                .build());
        return new Uploaded(saved.getId(), memeTag.name(), url, assetKey, true, false);
    }

    private static MemeTag parseTag(String tag) {
        if (tag == null || tag.isBlank()) {
            throw new MediaRejection(HttpStatus.BAD_REQUEST, "tag 가 필요합니다.");
        }
        try {
            return MemeTag.valueOf(tag.trim());
        } catch (IllegalArgumentException e) {
            throw new MediaRejection(HttpStatus.BAD_REQUEST,
                    "tag 는 " + Arrays.toString(MemeTag.values()) + " 중 하나여야 합니다.");
        }
    }

    private static String[] vocabulary(String csv, Set<String> allowed, String field) {
        String[] values = split(csv);
        for (String value : values) {
            if (!allowed.contains(value)) {
                throw new MediaRejection(HttpStatus.BAD_REQUEST, field + " 에 모르는 값이 있습니다: " + value);
            }
        }
        return values;
    }

    private static String[] keywords(String csv) {
        String[] values = split(csv);
        if (values.length > MAX_KEYWORDS) {
            throw new MediaRejection(HttpStatus.BAD_REQUEST, "keywords 는 " + MAX_KEYWORDS + "개 이하여야 합니다.");
        }
        for (String value : values) {
            if (value.length() > MAX_KEYWORD_LENGTH) {
                throw new MediaRejection(HttpStatus.BAD_REQUEST,
                        "keywords 항목은 " + MAX_KEYWORD_LENGTH + "자 이하여야 합니다.");
            }
        }
        return values;
    }

    /** 빈 칸과 중복을 없앤다. 넘기지 않으면 빈 배열이다 */
    private static String[] split(String csv) {
        if (csv == null || csv.isBlank()) {
            return new String[0];
        }
        List<String> parts = Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(part -> !part.isEmpty())
                .toList();
        return new LinkedHashSet<>(parts).toArray(new String[0]);
    }
}
