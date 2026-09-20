package com.ttegeoji.backend.jobs;

import com.ttegeoji.backend.domain.enums.SpiceLevel;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * kind 별 payload 모양(10 §3). 워커는 알 수 없는 필드를 거부한다(AI 저장소 contracts/jobs.py extra="forbid").
 * 참조(ID·version)만 담는다. 사유·댓글 원문은 넣지 않는다.
 */
public final class JobPayloads {

    private JobPayloads() {
    }

    public static Map<String, Object> prepare(String postId, long postVersion, long audienceVersion) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("post_id", postId);
        payload.put("post_version", postVersion);
        payload.put("audience_version", audienceVersion);
        return payload;
    }

    public static Map<String, Object> sentence(String verdictId, long verdictVersion, String postId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("verdict_id", verdictId);
        payload.put("verdict_version", verdictVersion);
        payload.put("post_id", postId);
        return payload;
    }

    // RETAIN 은 네 키가 모두 있어야 한다. 쓰지 않는 id 는 null(10 §3)
    public static Map<String, Object> retainVerdict(String verdictId, long version) {
        return retain(JobKind.EVENT_SENTENCE_FINALIZED, verdictId, null, version);
    }

    public static Map<String, Object> retainComment(String commentId, long version) {
        return retain(JobKind.EVENT_COMMENT_APPROVED, null, commentId, version);
    }

    private static Map<String, Object> retain(String event, String verdictId, String commentId, long version) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", event);
        payload.put("verdict_id", verdictId);
        payload.put("comment_id", commentId);
        payload.put("version", version);
        return payload;
    }

    /** 19 §3 네 키 모두 필수. voter_id 는 떼거지봇 id — 워커는 이 값을 jury-votes 요청에 그대로 되돌려 보낸다. */
    public static Map<String, Object> juryVote(String postId, long postVersion, String roomId, String voterId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("post_id", postId);
        payload.put("post_version", postVersion);
        payload.put("room_id", roomId);
        payload.put("voter_id", voterId);
        return payload;
    }

    /**
     * intensities 가 null 이면 키를 넣지 않는다(워커가 target_intensities 전체를 다시 쓴다).
     * 빈 목록은 워커가 거부하므로 여기서 막는다.
     */
    public static Map<String, Object> textRetry(String verdictId, long verdictVersion, int round,
                                                List<SpiceLevel> intensities) {
        if (intensities != null && intensities.isEmpty()) {
            throw new IllegalArgumentException("TEXT_RETRY intensities 는 null 이거나 1개 이상이어야 한다(10 §3)");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("verdict_id", verdictId);
        payload.put("verdict_version", verdictVersion);
        payload.put("round", round);
        if (intensities != null) {
            payload.put("intensities", intensities.stream().map(SpiceLevel::name).toList());
        }
        return payload;
    }
}
