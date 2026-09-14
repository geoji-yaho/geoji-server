package com.ttegeoji.backend.privacy;

import com.ttegeoji.backend.util.Json;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 판결 조회 차단 판정(10 §8 3문단). 파생 정리가 비동기여도 읽기 차단은 즉시다.
 * 게시물 삭제가 epoch 비교보다 우선한다("사건 삭제 시 판결 조회도 차단"). 잠그지 않고 읽는다.
 */
@Component
@RequiredArgsConstructor
public class VerdictReadGuard {

    public enum Decision {
        /** 저장된 문구를 그대로 보여도 된다 */
        ORIGINAL,
        /** 저장 뒤 scope epoch 가 바뀌어 과거 문구 대신 공개 가능한 템플릿 */
        TEMPLATE,
        /** 사건 삭제. 판결 조회 차단 */
        BLOCKED
    }

    private final InvalidationQueries queries;
    private final PrivacyEpochRepository epochs;

    /**
     * @param privacyEpochSnapshotJson verdict_texts.privacy_epoch_snapshot 원문 [{scope_key, epoch}]
     */
    public Decision decide(UUID postId, String privacyEpochSnapshotJson) {
        if (queries.isPostDeleted(postId)) {
            return Decision.BLOCKED;
        }
        if (privacyEpochSnapshotJson == null) {
            // TODO 게이트 A 답 대기: snapshot NULL(템플릿 저장 행·finalize 가 안 채운 행) 판정이 10 에 없다
            throw new UnsupportedOperationException("privacy_epoch_snapshot NULL 판정은 미결입니다.");
        }
        Map<String, Long> saved = parseSnapshot(privacyEpochSnapshotJson);
        Map<String, Long> current = epochs.read(saved.keySet());
        for (Map.Entry<String, Long> entry : saved.entrySet()) {
            if (!entry.getValue().equals(current.get(entry.getKey()))) {
                return Decision.TEMPLATE;
            }
        }
        return Decision.ORIGINAL;
    }

    static Map<String, Long> parseSnapshot(String json) {
        if (!(Json.read(json) instanceof List<?> items)) {
            throw new IllegalStateException("privacy_epoch_snapshot 은 배열이어야 합니다.");
        }
        Map<String, Long> saved = new TreeMap<>();
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> m) || !(m.get("scope_key") instanceof String key)
                    || !(m.get("epoch") instanceof Number epoch)) {
                throw new IllegalStateException("privacy_epoch_snapshot 원소는 {scope_key, epoch} 여야 합니다.");
            }
            saved.put(key, epoch.longValue());
        }
        return saved;
    }
}
