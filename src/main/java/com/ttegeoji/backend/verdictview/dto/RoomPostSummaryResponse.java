package com.ttegeoji.backend.verdictview.dto;

import com.ttegeoji.backend.domain.enums.VerdictType;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 방 피드 한 줄(S-06). 목록에 필요한 것만 담는다. 판결문·짤·투표 사유는 상세와 판결 조회가 준다.
 */
public record RoomPostSummaryResponse(
        UUID id,
        String postType,
        int amountKrw,
        String category,
        String item,
        UUID authorId,
        String authorNickname,
        OffsetDateTime voteDeadlineAt,
        OffsetDateTime createdAt,
        VerdictType juryStatus,
        PostDetailResponse.Tally tally,
        boolean voted
) {
}
