package com.ttegeoji.backend.dto;

import com.ttegeoji.backend.domain.Room;
import com.ttegeoji.backend.domain.enums.SpiceLevel;

import java.util.UUID;

public record RoomResponse(
        UUID id,
        String name,
        SpiceLevel spiceLevel,
        Integer voteDeadlineMinutes,
        String[] rules,
        String inviteCode,
        UUID createdBy
) {
    public static RoomResponse from(Room r) {
        return new RoomResponse(
                r.getId(), r.getName(), r.getSpiceLevel(), r.getVoteDeadlineMinutes(),
                r.getRules(), r.getInviteCode(), r.getCreatedBy());
    }
}
