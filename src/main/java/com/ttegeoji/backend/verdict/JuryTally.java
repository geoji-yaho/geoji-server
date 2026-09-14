package com.ttegeoji.backend.verdict;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ttegeoji.backend.domain.enums.SpiceLevel;
import com.ttegeoji.backend.domain.enums.VerdictType;

/**
 * 10 §3·§4.1 평결 집계. 정족수·동률·강도 규칙은 geoji-web SPEC.md 재판 규칙(9/14 결정)을 따른다.
 * 호출자가 보낸 평결을 믿지 않고 DB 에서 읽은 표로 다시 계산하는 데 쓴다.
 */
public final class JuryTally {

    private static final int QUORUM = 2;

    public record Vote(String verdict, String roomId) {
    }

    public record SharedRoom(String roomId, SpiceLevel spiceLevel, OffsetDateTime createdAt) {
    }

    public record Result(VerdictType result, Map<String, Integer> voteCounts, Double guiltyRatio,
                         List<SpiceLevel> targetIntensities, SpiceLevel defaultIntensity) {
    }

    private JuryTally() {
    }

    public static Result tally(String postType, List<Vote> votes, int eligibleCount, List<SharedRoom> rooms) {
        if (eligibleCount < 1) {
            throw new IllegalArgumentException("투표 가능 인원이 1 이상이어야 합니다: " + eligibleCount);
        }
        if (rooms == null || rooms.isEmpty()) {
            throw new IllegalArgumentException("공유 방이 없습니다");
        }
        List<Vote> cast = votes == null ? List.of() : votes;

        String yes;
        String no;
        VerdictType yesResult;
        VerdictType noResult;
        switch (postType == null ? "" : postType) {
            case "spent" -> {
                yes = "guilty";
                no = "notGuilty";
                yesResult = VerdictType.guilty;
                noResult = VerdictType.notGuilty;
            }
            case "considering" -> {
                yes = "agree";
                no = "disagree";
                yesResult = VerdictType.agree;
                noResult = VerdictType.disagree;
            }
            default -> throw new IllegalArgumentException("모르는 게시물 유형: " + postType);
        }

        Map<String, Integer> votesPerRoom = new HashMap<>();
        for (SharedRoom room : rooms) {
            votesPerRoom.putIfAbsent(room.roomId(), 0);
        }

        int yesCount = 0;
        int noCount = 0;
        for (Vote vote : cast) {
            if (yes.equals(vote.verdict())) {
                yesCount++;
            } else if (no.equals(vote.verdict())) {
                noCount++;
            } else {
                throw new IllegalArgumentException(postType + " 게시물에 맞지 않는 표: " + vote.verdict());
            }
            if (!votesPerRoom.containsKey(vote.roomId())) {
                throw new IllegalArgumentException("공유 방에 없는 room: " + vote.roomId());
            }
            votesPerRoom.merge(vote.roomId(), 1, Integer::sum);
        }

        Map<String, Integer> voteCounts = new LinkedHashMap<>();
        voteCounts.put(yes, yesCount);
        voteCounts.put(no, noCount);

        // 가능 인원이 정족수보다 적으면 전원이 투표했을 때 성립해야 한다
        int quorum = Math.min(QUORUM, eligibleCount);
        VerdictType result;
        if (cast.size() < quorum) {
            result = VerdictType.dismissed;
        } else {
            result = yesCount > noCount ? yesResult : noResult;
        }

        Double guiltyRatio = "spent".equals(postType) && !cast.isEmpty()
                ? (double) yesCount / (yesCount + noCount)
                : null;

        EnumSet<SpiceLevel> intensities = EnumSet.noneOf(SpiceLevel.class);
        SharedRoom top = null;
        for (SharedRoom room : rooms) {
            intensities.add(room.spiceLevel());
            if (top == null) {
                top = room;
                continue;
            }
            int cmp = Integer.compare(votesPerRoom.get(room.roomId()), votesPerRoom.get(top.roomId()));
            // 표 수가 같으면 먼저 만들어진 방, 그것도 같으면 입력 순서 앞(= 기존 top 유지)
            if (cmp > 0 || (cmp == 0 && room.createdAt().isBefore(top.createdAt()))) {
                top = room;
            }
        }

        return new Result(result, Collections.unmodifiableMap(voteCounts), guiltyRatio,
                List.copyOf(intensities), top.spiceLevel());
    }
}
