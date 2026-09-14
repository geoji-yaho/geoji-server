package com.ttegeoji.backend.jobs;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * JobEnqueuer 결과. dedupe 충돌이면 created=false 이고 id·deadlineAt 은 기존 행 값이다.
 * deadlineAt 은 SENTENCE·TEXT_RETRY 만 채워지고 나머지는 null.
 */
public record EnqueuedJob(UUID id, OffsetDateTime deadlineAt, boolean created) {
}
