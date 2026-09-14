package com.ttegeoji.backend.jobs;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * ai.jobs 한 행(001 DDL). 엔티티 없이 JdbcTemplate 으로 읽는다(code-layout 룰). payload 는 jsonb 문자열 그대로.
 */
public record JobRow(
        UUID id,
        UUID eventId,
        String eventType,
        JobKind kind,
        String dedupeKey,
        String aggregateId,
        long aggregateVersion,
        String payload,
        String status,
        int priority,
        int attempts,
        int maxAttempts,
        OffsetDateTime availableAt,
        OffsetDateTime deadlineAt,
        OffsetDateTime leaseUntil,
        String ownerId,
        UUID generationId,
        String lastErrorCode,
        String traceId,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
