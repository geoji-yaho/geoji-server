package com.ttegeoji.backend.jobs;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/**
 * 테스트용 ai.jobs 행을 직접 넣는다. 시각은 SQL 식("now() - interval '1 minute'" 등)으로 받는다.
 * RUNNING 은 001 CHECK 때문에 owner_id·generation_id·lease_until 이 채워져야 한다.
 */
final class JobRowFixtures {

    private final JdbcTemplate jdbcTemplate;

    JobRowFixtures(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    record Spec(JobKind kind, String status, String payloadJson, int attempts, int maxAttempts,
                String deadlineExpr, String leaseExpr, UUID generationId, String lastErrorCode) {
    }

    UUID insert(Spec spec) {
        UUID id = UUID.randomUUID();
        boolean running = "RUNNING".equals(spec.status());
        String sql = """
                INSERT INTO ai.jobs (id, event_id, event_type, kind, dedupe_key, aggregate_id, aggregate_version,
                                     payload, status, priority, attempts, max_attempts, deadline_at, lease_until,
                                     owner_id, generation_id, last_error_code, trace_id)
                VALUES (?, gen_random_uuid(), 'test.event', ?, ?, 'agg', 1, CAST(? AS jsonb), ?, 10, ?, ?, %s, %s,
                        ?, ?, ?, 'trace')
                """.formatted(spec.deadlineExpr() == null ? "NULL" : spec.deadlineExpr(),
                spec.leaseExpr() == null ? "NULL" : spec.leaseExpr());
        jdbcTemplate.update(sql, id, spec.kind().name(), "test:" + id, spec.payloadJson(), spec.status(),
                spec.attempts(), spec.maxAttempts(),
                running ? "worker-1" : null,
                running ? (spec.generationId() != null ? spec.generationId() : UUID.randomUUID()) : null,
                spec.lastErrorCode());
        return id;
    }

    /** lease 가 1분 전에 만료된 RUNNING 행. */
    UUID expiredRunning(JobKind kind, int attempts, int maxAttempts, String deadlineExpr, String lastErrorCode) {
        return insert(new Spec(kind, "RUNNING", "{}", attempts, maxAttempts, deadlineExpr,
                "now() - interval '1 minute'", null, lastErrorCode));
    }

    UUID withPayload(JobKind kind, String status, String payloadJson) {
        return insert(new Spec(kind, status, payloadJson, 0, 2, null,
                "RUNNING".equals(status) ? "now() + interval '1 minute'" : null, null, null));
    }

    String status(UUID id) {
        return jdbcTemplate.queryForObject("SELECT status FROM ai.jobs WHERE id = ?", String.class, id);
    }
}
