package com.ttegeoji.backend.verdictview.dto;

import com.ttegeoji.backend.domain.enums.SpiceLevel;
import com.ttegeoji.backend.domain.enums.VerdictType;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 게시물 한 건. 투표 화면(S-14)과 판결 화면(S-10)이 사건 개요와 배심원 집계를 그리는 데 쓴다.
 * 판결문·형량·짤은 여기 없다. 그것은 {@code GET /api/posts/{id}/verdict} 가 준다.
 *
 * <p>투표 사유는 평결이 확정되기 전에는 비운다. 확정 전에 남의 사유가 보이면 표가 쏠린다.
 */
public record PostDetailResponse(
        UUID id,
        String postType,
        int amountKrw,
        String category,
        String item,
        String reason,
        UUID authorId,
        String authorNickname,
        OffsetDateTime voteDeadlineAt,
        OffsetDateTime createdAt,
        List<RoomBrief> rooms,
        VerdictType juryStatus,
        Tally tally,
        List<VoteBrief> votes,
        VoteBrief myVote,
        boolean canVote,
        int eligibleVoterCount
) {

    /** 게시물이 공유된 방(철회되지 않은 것만) */
    public record RoomBrief(UUID id, String name, SpiceLevel spiceLevel) {
    }

    /** 반대(유죄·기각) 대 찬성(무죄·동의) */
    public record Tally(int oppose, int support) {
    }

    public record VoteBrief(
            UUID id,
            UUID voterId,
            String voterNickname,
            VerdictType verdict,
            String reason,
            OffsetDateTime createdAt
    ) {
    }
}
