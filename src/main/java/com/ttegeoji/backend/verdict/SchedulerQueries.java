package com.ttegeoji.backend.verdict;

import com.ttegeoji.backend.domain.enums.SpiceLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * deadline watchdog(10 §6)·재시도 round 스케줄러(10 §7)가 쓰는 조회. W2·W3 repository 를 고치지 않으려고 여기에 둔다.
 * 스캔은 잠그지 않고 후보만 읽는다. 잠금은 호출자 트랜잭션에서 privacy scope 다음에 verdict 행을 SKIP LOCKED 로 잡아
 * 여러 인스턴스가 같은 verdict 를 두고 기다리지 않게 한다. job 행은 그 뒤다(10 §2).
 */
@Component
@RequiredArgsConstructor
public class SchedulerQueries {

    /** 한 주기에 다루는 verdict 수 상한. 남은 것은 다음 주기에 */
    static final int SCAN_LIMIT = 100;

    public record OverdueVerdict(UUID verdictId, UUID postId) {
    }

    public record DueRetry(UUID verdictId, long verdictVersion, int round) {
    }

    public record ReapedRetry(UUID verdictId, int round) {
    }

    // 10 §6: 마감 지난 PENDING. deadline_at NULL(D-24 보류)은 NULL < now() 가 참이 아니라 빠진다
    private static final String OVERDUE_WHERE = """
            v.sentence_status = 'PENDING' AND v.deadline_at IS NOT NULL AND v.deadline_at < now()
            """;

    // 10 §7: 예약 시각이 지났고 round 1~3·템플릿 노출 중·진행 중 TEXT_RETRY 없음
    private static final String DUE_RETRY_WHERE = """
            v.pending_retry_at <= now() AND v.retry_round BETWEEN 1 AND 3
            AND v.sentence_status = 'FINAL' AND v.text_status = 'TEMPLATE_READY'
            AND NOT EXISTS (SELECT 1 FROM ai.jobs a
                             WHERE a.kind = 'TEXT_RETRY' AND a.aggregate_id = v.id::text
                               AND a.status IN ('QUEUED', 'RUNNING'))
            """;

    // 10 §7: reaper 가 FAILED 로 바꾼 현재 round 의 TEXT_RETRY 인데 다음 round 가 아직 없음.
    // generation-failed 가 먼저 처리했으면 retry_round 가 올라갔거나(round < 3) active 가 비었다(round 3).
    // 다음 round 를 잡거나 WARN 을 남기면 retry_round 가 오르거나 active 가 비어 다시 걸리지 않는다.
    // begin-generation 전에 회수된 job(active NULL)은 round < 3 일 때만 잡는다. round 3 이면 이미 처리한 것과 구분이 안 된다
    private static final String REAPED_RETRY_WHERE = """
            v.sentence_status = 'FINAL' AND v.text_status = 'TEMPLATE_READY' AND v.pending_retry_at IS NULL
            AND v.retry_round BETWEEN 1 AND 3
            AND EXISTS (SELECT 1 FROM ai.jobs j
                         WHERE j.kind = 'TEXT_RETRY' AND j.status = 'FAILED' AND j.last_error_code = 'LEASE_EXPIRED'
                           AND j.aggregate_id = v.id::text AND j.aggregate_version = v.verdict_version
                           AND j.payload->>'round' = v.retry_round::text
                           AND (v.active_job_id = j.id OR (v.active_job_id IS NULL AND v.retry_round < 3)))
            AND NOT EXISTS (SELECT 1 FROM ai.jobs a
                             WHERE a.kind = 'TEXT_RETRY' AND a.aggregate_id = v.id::text
                               AND a.status IN ('QUEUED', 'RUNNING'))
            """;

    private final JdbcTemplate jdbcTemplate;

    // ---- 10 §6 watchdog ----

    public List<OverdueVerdict> overduePendingVerdicts() {
        return jdbcTemplate.query("SELECT v.id, v.post_id FROM verdicts v WHERE " + OVERDUE_WHERE
                                  + " ORDER BY v.deadline_at LIMIT " + SCAN_LIMIT,
                (rs, i) -> new OverdueVerdict(rs.getObject("id", UUID.class), rs.getObject("post_id", UUID.class)));
    }

    /** privacy scope 를 잠근 뒤 부른다. 다른 트랜잭션이 잡고 있거나 이미 확정됐으면 false(이번 주기는 건너뛴다). */
    public boolean lockOverduePending(UUID verdictId) {
        return !jdbcTemplate.query("SELECT v.id FROM verdicts v WHERE v.id = ? AND " + OVERDUE_WHERE
                                   + " FOR UPDATE OF v SKIP LOCKED",
                (rs, i) -> rs.getObject("id", UUID.class), verdictId).isEmpty();
    }

    /** 그 verdict 의 끝나지 않은 SENTENCE job. begin-generation 전이라 active_job_id 가 비어 있는 job 도 찾는다. */
    public List<UUID> openSentenceJobIds(UUID verdictId) {
        return jdbcTemplate.query("""
                SELECT id FROM ai.jobs
                 WHERE kind = 'SENTENCE' AND aggregate_id = ? AND status IN ('QUEUED', 'RUNNING')
                """, (rs, i) -> rs.getObject("id", UUID.class), verdictId.toString());
    }

    // ---- 10 §7 재시도 round ----

    public List<UUID> dueRetryVerdictIds() {
        return jdbcTemplate.query("SELECT v.id FROM verdicts v WHERE " + DUE_RETRY_WHERE
                                  + " ORDER BY v.pending_retry_at LIMIT " + SCAN_LIMIT,
                (rs, i) -> rs.getObject("id", UUID.class));
    }

    public Optional<DueRetry> lockDueRetry(UUID verdictId) {
        return jdbcTemplate.query("SELECT v.id, v.verdict_version, v.retry_round FROM verdicts v WHERE v.id = ? AND "
                                  + DUE_RETRY_WHERE + " FOR UPDATE OF v SKIP LOCKED",
                (rs, i) -> new DueRetry(rs.getObject("id", UUID.class), rs.getLong("verdict_version"),
                        rs.getInt("retry_round")), verdictId).stream().findFirst();
    }

    /** 문구가 TEMPLATE 인 강도(10 §3·§5 10단계 TEXT_RETRY payload intensities). */
    public List<SpiceLevel> templateIntensities(UUID verdictId) {
        return jdbcTemplate.query("""
                SELECT intensity::text AS intensity FROM verdict_texts
                 WHERE verdict_id = ? AND source = 'TEMPLATE'
                 ORDER BY intensity
                """, (rs, i) -> SpiceLevel.valueOf(rs.getString("intensity")), verdictId);
    }

    public void clearPendingRetry(UUID verdictId) {
        jdbcTemplate.update("UPDATE verdicts SET pending_retry_at = NULL WHERE id = ?", verdictId);
    }

    public List<UUID> reapedRetryVerdictIds() {
        return jdbcTemplate.query("SELECT v.id FROM verdicts v WHERE " + REAPED_RETRY_WHERE
                                  + " ORDER BY v.id LIMIT " + SCAN_LIMIT,
                (rs, i) -> rs.getObject("id", UUID.class));
    }

    public Optional<ReapedRetry> lockReapedRetry(UUID verdictId) {
        return jdbcTemplate.query("SELECT v.id, v.retry_round FROM verdicts v WHERE v.id = ? AND "
                                  + REAPED_RETRY_WHERE + " FOR UPDATE OF v SKIP LOCKED",
                (rs, i) -> new ReapedRetry(rs.getObject("id", UUID.class), rs.getInt("retry_round")), verdictId)
                .stream().findFirst();
    }

    /** 다음 round 를 DB now() + delay 로 예약하고 회수된 generation 을 비운다. */
    public void scheduleRound(UUID verdictId, int round, Duration delay) {
        jdbcTemplate.update("""
                UPDATE verdicts
                   SET retry_round = ?, pending_retry_at = now() + make_interval(secs => CAST(? AS double precision)),
                       active_job_id = NULL, active_generation_id = NULL
                 WHERE id = ?
                """, round, delay.toSeconds(), verdictId);
    }

    /** 마지막 round 회수 뒤. 예약 없이 회수된 generation 만 비운다. */
    public void clearActiveGeneration(UUID verdictId) {
        jdbcTemplate.update("""
                UPDATE verdicts SET pending_retry_at = NULL, active_job_id = NULL, active_generation_id = NULL
                 WHERE id = ?
                """, verdictId);
    }
}
