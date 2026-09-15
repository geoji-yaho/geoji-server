package com.ttegeoji.backend.dto;

import com.ttegeoji.backend.domain.Room;
import com.ttegeoji.backend.domain.enums.SpiceLevel;

import java.util.UUID;

/**
 * 초대장 화면(참가 전) 미리보기. 아직 멤버가 아닌 사람도 볼 수 있어서 방 안의 지출·댓글은 넣지 않는다.
 * {@code alreadyMember} 가 true 면 프론트는 "참가하기" 대신 바로 방으로 보내면 된다.
 */
public record RoomInvitePreviewResponse(
        UUID id,
        String name,
        SpiceLevel spiceLevel,
        Integer voteDeadlineMinutes,
        String[] rules,
        String ownerNickname,
        long memberCount,
        boolean alreadyMember
) {
    public static RoomInvitePreviewResponse from(Room room, String ownerNickname, long memberCount,
                                                 boolean alreadyMember) {
        return new RoomInvitePreviewResponse(
                room.getId(), room.getName(), room.getSpiceLevel(), room.getVoteDeadlineMinutes(),
                room.getRules() == null ? new String[0] : room.getRules(),
                ownerNickname, memberCount, alreadyMember);
    }
}
