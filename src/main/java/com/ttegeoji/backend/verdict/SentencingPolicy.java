package com.ttegeoji.backend.verdict;

import java.util.List;

/**
 * 10 §3·§5 policy_snapshot 의 양형 밴드(geoji-web SPEC.md, 9/14 결정). 유죄일 때만 쓴다.
 * JSON 키(allowed_sentences 등)로 펴는 것은 snapshot 을 조립하는 호출자 몫이다.
 * 형량은 아직 Sentence enum 이 없어 문자열 코드로 둔다.
 */
public final class SentencingPolicy {

    public static final String VERSION = "sentencing-band-v1";
    public static final boolean REASON_REQUIRED = true;

    public record AllowedSentence(String code, int rank) {
    }

    public record Snapshot(String version, List<AllowedSentence> allowedSentences, String fallbackSentence,
                           boolean reasonRequired) {
        public Snapshot {
            allowedSentences = List.copyOf(allowedSentences);
        }
    }

    private static final AllowedSentence PROBATION = new AllowedSentence("probation", 1);
    private static final AllowedSentence ONE_DAY = new AllowedSentence("oneDay", 2);
    private static final AllowedSentence LIFE = new AllowedSentence("life", 3);

    private SentencingPolicy() {
    }

    public static Snapshot forGuiltyRatio(double ratio) {
        if (Double.isNaN(ratio) || ratio < 0.5 || ratio > 1.0) {
            throw new IllegalArgumentException("양형 밴드 밖의 유죄율: " + ratio);
        }
        List<AllowedSentence> allowed;
        if (ratio >= 0.9) {
            allowed = List.of(PROBATION, ONE_DAY, LIFE);
        } else if (ratio >= 0.7) {
            allowed = List.of(PROBATION, ONE_DAY);
        } else {
            allowed = List.of(PROBATION);
        }
        // fallback 은 밴드 상한
        return new Snapshot(VERSION, allowed, allowed.get(allowed.size() - 1).code(), REASON_REQUIRED);
    }
}
