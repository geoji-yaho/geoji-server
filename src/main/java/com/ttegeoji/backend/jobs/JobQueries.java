package com.ttegeoji.backend.jobs;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * ai.jobs 조회·상태 변경(10 §4.1·§6·§8). 트랜잭션은 호출자가 연다. 잠금 순서상 job 행은 마지막이므로(10 §2)
 * 호출자는 privacy scope·verdict 를 먼저 잠근 뒤 이 메서드를 부른다.
 */
@Component
@RequiredArgsConstructor
public class JobQueries {

    private static final String COLUMNS = """
            id, event_id, event_type, kind, dedupe_key, aggregate_id, aggregate_version, payload::text AS payload,
            status, priority, attempts, max_attempts, available_at, deadline_at, lease_until, owner_id,
            generation_id, last_error_code, trace_id, created_at, updated_at
            """;

    private static final RowMapper<JobRow> JOB_ROW = (rs, i) -> new JobRow(
            rs.getObject("id", UUID.class),
            rs.getObject("event_id", UUID.class),
            rs.getString("event_type"),
            JobKind.valueOf(rs.getString("kind")),
            rs.getString("dedupe_key"),
            rs.getString("aggregate_id"),
            rs.getLong("aggregate_version"),
            rs.getString("payload"),
            rs.getString("status"),
            rs.getInt("priority"),
            rs.getInt("attempts"),
            rs.getInt("max_attempts"),
            rs.getObject("available_at", OffsetDateTime.class),
            rs.getObject("deadline_at", OffsetDateTime.class),
            rs.getObject("lease_until", OffsetDateTime.class),
            rs.getString("owner_id"),
            rs.getObject("generation_id", UUID.class),
            rs.getString("last_error_code"),
            rs.getString("trace_id"),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("updated_at", OffsetDateTime.class));

    private final JdbcTemplate jdbcTemplate;

    public Optional<JobRow> findJob(UUID jobId) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM ai.jobs WHERE id = ?", JOB_ROW, jobId)
                .stream().findFirst();
    }

    /** 10 §4.1 검증: job 존재 ∧ RUNNING ∧ generation 일치 ∧ lease 유효(DB now() 기준). 아니면 빈 결과 → 409 STALE_GENERATION. */
    public Optional<JobRow> findRunningWithValidLease(UUID jobId, UUID generationId) {
        return jdbcTemplate.query("SELECT " + COLUMNS + """
                         FROM ai.jobs
                        WHERE id = ? AND status = 'RUNNING' AND generation_id = ? AND lease_until > now()
                        """, JOB_ROW, jobId, generationId)
                .stream().findFirst();
    }

    /** 10 §3 D-24 SENTENCE 게이트: 같은 post 의 PREPARE 가 QUEUED·RUNNING 인가. */
    public boolean isPrepareActive(String postId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM ai.jobs
                                WHERE kind = 'PREPARE' AND payload->>'post_id' = ?
                                  AND status IN ('QUEUED', 'RUNNING'))
                """, Boolean.class, postId));
    }

    /**
     * 10 §8 D-26: 무효화 트랜잭션에서 그 post 의 진행 중 PREPARE·SENTENCE·TEXT_RETRY 를 끈다.
     * TEXT_RETRY payload 에는 post_id 가 없어 호출자가 넘긴 verdictIds(그 post 의 verdict)로 찾는다. RETAIN 은 건드리지 않는다.
     * 호출자는 privacy epoch 를 먼저 올린 뒤 부른다.
     *
     * @return CANCELLED 로 바뀐 job id
     */
    public List<UUID> cancelActiveForPost(String postId, Collection<String> verdictIds) {
        String[] verdicts = verdictIds == null ? new String[0] : verdictIds.toArray(String[]::new);
        return jdbcTemplate.query(con -> {
            var ps = con.prepareStatement("""
                    UPDATE ai.jobs
                       SET status = 'CANCELLED', owner_id = NULL, generation_id = NULL, lease_until = NULL,
                           updated_at = now()
                     WHERE status IN ('QUEUED', 'RUNNING')
                       AND ((kind IN ('PREPARE', 'SENTENCE') AND payload->>'post_id' = ?)
                            OR (kind = 'TEXT_RETRY' AND payload->>'verdict_id' = ANY (?)))
                    RETURNING id
                    """);
            ps.setString(1, postId);
            ps.setArray(2, con.createArrayOf("text", verdicts));
            return ps;
        }, (rs, i) -> rs.getObject("id", UUID.class));
    }

    /**
     * 10 §6 4단계: watchdog 이 이전 job 을 CANCELLED 로 바꾼다. 이미 끝난 job(SUCCEEDED·FAILED·CANCELLED)은 그대로 둔다.
     *
     * @return 바뀌었으면 true
     */
    public boolean cancel(UUID jobId) {
        return jdbcTemplate.update("""
                UPDATE ai.jobs
                   SET status = 'CANCELLED', owner_id = NULL, generation_id = NULL, lease_until = NULL,
                       updated_at = now()
                 WHERE id = ? AND status IN ('QUEUED', 'RUNNING')
                """, jobId) == 1;
    }
}
