package com.ttegeoji.backend.verdictview.dto;

import java.util.UUID;

/** SPEC S-14 투표. 검증은 서비스가 순서대로 한다(404 → 403 → 400 → 409) */
public record PostVoteRequest(String verdict, String reason, UUID roomId) {
}
