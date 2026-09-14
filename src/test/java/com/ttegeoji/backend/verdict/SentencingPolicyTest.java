package com.ttegeoji.backend.verdict;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SentencingPolicyTest {

    private static SentencingPolicy.AllowedSentence s(String code, int rank) {
        return new SentencingPolicy.AllowedSentence(code, rank);
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.5, 0.69})
    @DisplayName("10 §3 유죄율 50~69% → [probation], fallback probation")
    void band1(double ratio) {
        SentencingPolicy.Snapshot snapshot = SentencingPolicy.forGuiltyRatio(ratio);

        assertThat(snapshot.allowedSentences()).containsExactly(s("probation", 1));
        assertThat(snapshot.fallbackSentence()).isEqualTo("probation");
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.7, 0.89})
    @DisplayName("10 §3 유죄율 70~89% → [probation, oneDay], fallback oneDay")
    void band2(double ratio) {
        SentencingPolicy.Snapshot snapshot = SentencingPolicy.forGuiltyRatio(ratio);

        assertThat(snapshot.allowedSentences()).containsExactly(s("probation", 1), s("oneDay", 2));
        assertThat(snapshot.fallbackSentence()).isEqualTo("oneDay");
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.9, 1.0})
    @DisplayName("10 §3 유죄율 90%+ → [probation, oneDay, life], fallback life")
    void band3(double ratio) {
        SentencingPolicy.Snapshot snapshot = SentencingPolicy.forGuiltyRatio(ratio);

        assertThat(snapshot.allowedSentences()).containsExactly(s("probation", 1), s("oneDay", 2), s("life", 3));
        assertThat(snapshot.fallbackSentence()).isEqualTo("life");
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.5, 0.75, 0.95})
    @DisplayName("10 §5 fallback 은 rank 가 가장 큰 형량, 목록은 rank 오름차순·불변")
    void fallbackIsTopRank(double ratio) {
        SentencingPolicy.Snapshot snapshot = SentencingPolicy.forGuiltyRatio(ratio);
        var allowed = snapshot.allowedSentences();

        for (int i = 1; i < allowed.size(); i++) {
            assertThat(allowed.get(i).rank()).isGreaterThan(allowed.get(i - 1).rank());
        }
        assertThat(snapshot.fallbackSentence()).isEqualTo(allowed.get(allowed.size() - 1).code());
        assertThatThrownBy(() -> allowed.add(s("x", 9))).isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.49, -0.1, 1.01, Double.NaN})
    @DisplayName("10 §3 유죄율이 밴드 밖(0.5 미만·1 초과·NaN) → IllegalArgumentException")
    void outOfBandRejected(double ratio) {
        assertThatThrownBy(() -> SentencingPolicy.forGuiltyRatio(ratio))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("10 §3 정책 스냅샷 version sentencing-band-v1, reason_required true")
    void versionAndReasonRequired() {
        SentencingPolicy.Snapshot snapshot = SentencingPolicy.forGuiltyRatio(0.75);

        assertThat(SentencingPolicy.VERSION).isEqualTo("sentencing-band-v1");
        assertThat(SentencingPolicy.REASON_REQUIRED).isTrue();
        assertThat(snapshot.version()).isEqualTo("sentencing-band-v1");
        assertThat(snapshot.reasonRequired()).isTrue();
    }
}
