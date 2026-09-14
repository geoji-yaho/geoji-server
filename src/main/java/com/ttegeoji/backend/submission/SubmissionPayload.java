package com.ttegeoji.backend.submission;

import com.ttegeoji.backend.domain.enums.PostType;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 정규화된 제출 값(10 §9). item·reason 은 NFC 뒤 앞뒤 공백 제거, 빈 reason 은 null, 공유 방은 중복 제거·정렬.
 * payload_hash 는 이 값의 sha256. AI API 는 이 값을 비교하지 않고 받기만 하므로 백엔드 내부 규칙이다.
 */
public record SubmissionPayload(PostType postType, int amountKrw, String category, String item, String reason,
                                List<UUID> roomIds) {

    // 10 §15.2 카테고리 11종 고정(posts.category CHECK 와 같다)
    public static final Set<String> CATEGORIES = Set.of(
            "식비", "배달", "카페/간식", "교통/택시", "쇼핑/패션", "뷰티", "취미/여가", "술/유흥", "구독", "생활", "기타");
    static final int ITEM_MAX = 30;
    static final int REASON_MAX = 200;

    /** 입력 검증(10 §4 필수값 규칙과 같은 기준) 뒤 정규화. 위반은 IllegalArgumentException → 400. */
    public static SubmissionPayload normalize(PostType postType, Integer amountKrw, String category, String item,
                                              String reason, List<UUID> roomIds) {
        if (postType == null) {
            throw new IllegalArgumentException("post_type 은 spent 또는 considering 이어야 합니다.");
        }
        if (amountKrw == null || amountKrw <= 0) {
            throw new IllegalArgumentException("amount_krw 는 0보다 커야 합니다.");
        }
        if (category == null || !CATEGORIES.contains(category)) {
            throw new IllegalArgumentException("category 는 정해진 11종 중 하나여야 합니다.");
        }
        String normalizedItem = clean(item);
        int itemLength = normalizedItem == null ? 0 : normalizedItem.codePointCount(0, normalizedItem.length());
        if (itemLength < 1 || itemLength > ITEM_MAX) {
            throw new IllegalArgumentException("item 은 공백 제거 뒤 1~30자여야 합니다.");
        }
        String normalizedReason = clean(reason);
        if (normalizedReason != null
                && normalizedReason.codePointCount(0, normalizedReason.length()) > REASON_MAX) {
            throw new IllegalArgumentException("reason 은 200자 이하여야 합니다.");
        }
        if (roomIds == null || roomIds.isEmpty() || roomIds.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("room_ids 에 공유할 방이 하나 이상 있어야 합니다.");
        }
        List<UUID> rooms = roomIds.stream().distinct().sorted().toList();
        return new SubmissionPayload(postType, amountKrw, category, normalizedItem, normalizedReason, rooms);
    }

    private static String clean(String value) {
        if (value == null) {
            return null;
        }
        String stripped = Normalizer.normalize(value, Normalizer.Form.NFC).strip();
        return stripped.isEmpty() ? null : stripped;
    }

    /** 정규화된 타입·금액·item·reason·category·정렬된 방 목록의 sha256 소문자 hex. 필드는 길이 접두로 구분한다. */
    public String hash() {
        StringBuilder canonical = new StringBuilder();
        append(canonical, postType.name());
        append(canonical, Integer.toString(amountKrw));
        append(canonical, item);
        append(canonical, reason);
        append(canonical, category);
        for (UUID roomId : roomIds) {
            append(canonical, roomId.toString());
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 을 쓸 수 없습니다", e);
        }
    }

    // null 과 빈 문자열이 같은 hash 가 되지 않게 null 은 -1 로 적는다
    private static void append(StringBuilder out, String value) {
        if (value == null) {
            out.append("-1:");
        } else {
            out.append(value.length()).append(':').append(value);
        }
        out.append('|');
    }
}
