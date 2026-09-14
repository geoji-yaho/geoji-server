package com.ttegeoji.backend.verdict;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import com.ttegeoji.backend.domain.enums.SpiceLevel;
import com.ttegeoji.backend.domain.enums.VerdictType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

class JuryTallyTest {

    private static final OffsetDateTime T0 = OffsetDateTime.of(2026, 9, 1, 0, 0, 0, 0, ZoneOffset.UTC);

    private static JuryTally.SharedRoom room(String id, SpiceLevel level, int minutesAfterT0) {
        return new JuryTally.SharedRoom(id, level, T0.plusMinutes(minutesAfterT0));
    }

    private static JuryTally.Vote vote(String verdict, String roomId) {
        return new JuryTally.Vote(verdict, roomId);
    }

    @Test
    @DisplayName("10 §3 정족수: 가능 3명 중 1표 → dismissed")
    void belowQuorumDismissed() {
        JuryTally.Result result = JuryTally.tally("spent",
                List.of(vote("guilty", "r1")), 3, List.of(room("r1", SpiceLevel.mild, 0)));

        assertThat(result.result()).isEqualTo(VerdictType.dismissed);
        assertThat(result.guiltyRatio()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("10 §3 정족수: 가능 1명·1표 → 정족수 충족(dismissed 아님)")
    void singleEligibleMeetsQuorum() {
        JuryTally.Result result = JuryTally.tally("spent",
                List.of(vote("guilty", "r1")), 1, List.of(room("r1", SpiceLevel.mild, 0)));

        assertThat(result.result()).isEqualTo(VerdictType.guilty);
    }

    @Test
    @DisplayName("10 §3 spent 2:2 동률 → notGuilty")
    void spentTieNotGuilty() {
        JuryTally.Result result = JuryTally.tally("spent",
                List.of(vote("guilty", "r1"), vote("guilty", "r1"), vote("notGuilty", "r1"), vote("notGuilty", "r1")),
                5, List.of(room("r1", SpiceLevel.spicy, 0)));

        assertThat(result.result()).isEqualTo(VerdictType.notGuilty);
        assertThat(result.guiltyRatio()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("10 §3 considering 2:2 동률 → disagree, guiltyRatio null")
    void consideringTieDisagree() {
        JuryTally.Result result = JuryTally.tally("considering",
                List.of(vote("agree", "r1"), vote("agree", "r1"), vote("disagree", "r1"), vote("disagree", "r1")),
                5, List.of(room("r1", SpiceLevel.spicy, 0)));

        assertThat(result.result()).isEqualTo(VerdictType.disagree);
        assertThat(result.voteCounts()).containsExactly(entry("agree", 2), entry("disagree", 2));
        assertThat(result.guiltyRatio()).isNull();
    }

    @Test
    @DisplayName("10 §4.1 spent 3:1 → guilty, guiltyRatio 0.75, voteCounts {guilty:3, notGuilty:1}")
    void guiltyThreeToOne() {
        JuryTally.Result result = JuryTally.tally("spent",
                List.of(vote("guilty", "r1"), vote("guilty", "r1"), vote("guilty", "r1"), vote("notGuilty", "r1")),
                4, List.of(room("r1", SpiceLevel.hell, 0)));

        assertThat(result.result()).isEqualTo(VerdictType.guilty);
        assertThat(result.guiltyRatio()).isEqualTo(0.75);
        assertThat(result.voteCounts()).containsExactly(entry("guilty", 3), entry("notGuilty", 1));
    }

    @Test
    @DisplayName("10 §4.1 voteCounts 는 0 표인 키도 둔다")
    void voteCountsKeepZero() {
        JuryTally.Result result = JuryTally.tally("spent",
                List.of(vote("guilty", "r1"), vote("guilty", "r1")),
                2, List.of(room("r1", SpiceLevel.mild, 0)));

        assertThat(result.voteCounts()).containsExactly(entry("guilty", 2), entry("notGuilty", 0));
    }

    @Test
    @DisplayName("10 §3 target_intensities = 공유 방 강도 중복 제거, mild→spicy→hell 순")
    void targetIntensitiesDistinctOrdered() {
        JuryTally.Result result = JuryTally.tally("spent",
                List.of(vote("guilty", "r1"), vote("guilty", "r2")), 4,
                List.of(room("r1", SpiceLevel.hell, 0), room("r2", SpiceLevel.mild, 1),
                        room("r3", SpiceLevel.hell, 2), room("r4", SpiceLevel.spicy, 3)));

        assertThat(result.targetIntensities())
                .containsExactly(SpiceLevel.mild, SpiceLevel.spicy, SpiceLevel.hell);
    }

    @Test
    @DisplayName("10 §3 default_intensity = 표 최다 방의 강도")
    void defaultIntensityMostVotedRoom() {
        JuryTally.Result result = JuryTally.tally("spent",
                List.of(vote("guilty", "r1"), vote("guilty", "r2"), vote("notGuilty", "r2")), 4,
                List.of(room("r1", SpiceLevel.mild, 0), room("r2", SpiceLevel.hell, 1)));

        assertThat(result.defaultIntensity()).isEqualTo(SpiceLevel.hell);
    }

    @Test
    @DisplayName("10 §3 최다 방 동률이면 created_at 이른 방의 강도")
    void defaultIntensityTieEarliestRoom() {
        JuryTally.Result result = JuryTally.tally("spent",
                List.of(vote("guilty", "late"), vote("guilty", "early")), 4,
                List.of(room("late", SpiceLevel.hell, 10), room("early", SpiceLevel.spicy, 5)));

        assertThat(result.defaultIntensity()).isEqualTo(SpiceLevel.spicy);
    }

    @Test
    @DisplayName("10 §3 게시물 유형에 맞지 않는 표·공유 방에 없는 room·방 없음·가능 인원 0 → IllegalArgumentException")
    void invalidInputRejected() {
        List<JuryTally.SharedRoom> rooms = List.of(room("r1", SpiceLevel.mild, 0));

        assertThatThrownBy(() -> JuryTally.tally("spent", List.of(vote("agree", "r1")), 2, rooms))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JuryTally.tally("considering", List.of(vote("guilty", "r1")), 2, rooms))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JuryTally.tally("spent", List.of(vote("guilty", "nope")), 2, rooms))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JuryTally.tally("spent", List.of(), 2, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JuryTally.tally("spent", List.of(), 0, rooms))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
