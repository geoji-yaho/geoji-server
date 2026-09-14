package com.ttegeoji.backend.jobs;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * lease 만료 RUNNING job 회수(10 §7). SQL 은 AI 저장소 adapters/postgres_jobs.py REAPER_SQL 원문에 RETURNING 만 붙였다.
 * 워커는 lease_expired_total 을 볼 수 없으므로 회수 건수를 kind·결과 상태별 INFO 로그로 남긴다(10 §14 답).
 * TEXT_RETRY 를 FAILED 로 바꾼 뒤 다음 round 예약과 SENTENCE CANCELLED 뒤 폴백은 각각 재시도 스케줄러·watchdog 몫이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReaperScheduler {

    static final String REAPER_SQL = """
            UPDATE ai.jobs SET status = CASE
                WHEN kind = 'TEXT_RETRY' THEN 'FAILED'
                WHEN kind = 'SENTENCE' AND (deadline_at IS NULL OR deadline_at <= now()) THEN 'CANCELLED'
                WHEN attempts >= max_attempts THEN 'FAILED'
                ELSE 'QUEUED' END,
              owner_id = NULL, generation_id = NULL, lease_until = NULL,
              last_error_code = COALESCE(last_error_code, 'LEASE_EXPIRED'), updated_at = now()
            WHERE status = 'RUNNING' AND lease_until < now()
            RETURNING kind, status
            """;

    /** 회수 결과 한 묶음. */
    public record Reaped(String kind, String status, int count) {
    }

    private final JdbcTemplate jdbcTemplate;

    private record Key(String kind, String status) {
    }

    @Scheduled(fixedDelay = 5000)
    public List<Reaped> reap() {
        Map<Key, Integer> counts = new TreeMap<>(
                Comparator.comparing(Key::kind).thenComparing(Key::status));
        jdbcTemplate.query(REAPER_SQL,
                (RowCallbackHandler) rs -> counts.merge(new Key(rs.getString("kind"), rs.getString("status")),
                        1, Integer::sum));
        List<Reaped> reaped = counts.entrySet().stream()
                .map(e -> new Reaped(e.getKey().kind(), e.getKey().status(), e.getValue()))
                .toList();
        for (Reaped r : reaped) {
            log.info("lease_expired_total kind={} status={} count={}", r.kind(), r.status(), r.count());
        }
        return reaped;
    }
}
