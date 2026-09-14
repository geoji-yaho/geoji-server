package com.ttegeoji.backend.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// DB 없음. jury.vote_counts·guilty_ratio 순수 계산
class CaseSnapshotRatioTest {

    @Test
    @DisplayName("10 §4.1 spent guilty 3·notGuilty 1 → guilty_ratio 0.75(백분율 아님)")
    void spentRatio() {
        assertThat(CaseSnapshotAssembler.guiltyRatio("spent", Map.of("guilty", 3, "notGuilty", 1))).isEqualTo(0.75);
        assertThat(CaseSnapshotAssembler.guiltyRatio("spent", Map.of("guilty", 1, "notGuilty", 3))).isEqualTo(0.25);
    }

    @Test
    @DisplayName("10 §4.1 + 9/15 결정 considering agree 1·disagree 3 → guilty_ratio 0.75(기각 비율)")
    void consideringRatio() {
        assertThat(CaseSnapshotAssembler.guiltyRatio("considering", Map.of("agree", 1, "disagree", 3)))
                .isEqualTo(0.75);
    }

    @Test
    @DisplayName("9/15 결정 표 0 → guilty_ratio 0")
    void zeroVotes() {
        assertThat(CaseSnapshotAssembler.guiltyRatio("spent", Map.of())).isEqualTo(0.0);
        assertThat(CaseSnapshotAssembler.guiltyRatio("considering", Map.of("agree", 0, "disagree", 0))).isEqualTo(0.0);
    }

    @Test
    @DisplayName("10 §4.1 vote_counts 는 유형별 두 키를 0 포함으로 채우고 다른 값의 표는 세지 않는다")
    void voteCountsFillsZeros() {
        assertThat(CaseSnapshotAssembler.voteCounts("spent", Map.of("guilty", 2, "dismissed", 5)))
                .containsExactly(Map.entry("guilty", 2), Map.entry("notGuilty", 0));
        assertThat(CaseSnapshotAssembler.voteCounts("considering", Map.of()))
                .containsExactly(Map.entry("agree", 0), Map.entry("disagree", 0));
    }

    @Test
    @DisplayName("모르는 게시물 유형은 계산하지 않는다")
    void unknownPostType() {
        assertThatThrownBy(() -> CaseSnapshotAssembler.guiltyRatio("no_spend", Map.of()))
                .isInstanceOf(CaseSnapshotAssembler.SnapshotAssemblyException.class);
    }
}
