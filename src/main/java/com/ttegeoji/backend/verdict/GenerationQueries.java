package com.ttegeoji.backend.verdict;

import com.ttegeoji.backend.jobs.JobKind;
import com.ttegeoji.backend.privacy.ScopeKeys;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * begin-generation·generation-failed·폴백이 쓰는 조회(10 §4.3·§4.6). W2 repository·엔티티를 고치지 않으려고 여기에 둔다.
 * 트랜잭션은 호출자가 연다. job 행 잠금은 잠금 순서의 마지막이다(privacy scope → verdict → job, 10 §2).
 */
@Component
@RequiredArgsConstructor
public class GenerationQueries {

    /** 잠근 job 행. leaseValid = RUNNING ∧ lease_until > DB now() */
    public record LockedJob(UUID id, JobKind kind, String aggregateId, String status, UUID generationId,
                            OffsetDateTime deadlineAt, boolean leaseValid) {

        /** 10 §4.1·§4.3 검증: RUNNING ∧ generation 일치 ∧ lease 유효 */
        public boolean ownedBy(UUID generation) {
            return leaseValid && generation != null && generation.equals(generationId);
        }
    }

    public record JuryCounts(int juryCount, int guiltyCount) {
    }

    private final JdbcTemplate jdbcTemplate;

    /** 잠그지 않고 post_id 만 읽는다. scope key 를 verdict 잠금보다 먼저 알아야 해서 따로 읽는다. 없으면 빈 결과(404). */
    public Optional<UUID> findPostId(UUID verdictId) {
        return jdbcTemplate.query("SELECT post_id FROM verdicts WHERE id = ?",
                (rs, i) -> rs.getObject("post_id", UUID.class), verdictId).stream().findFirst();
    }

    /** 사건의 privacy scope key: post·작성자·공유 방 전부(10 §4.1 privacy_versions 와 같은 범위). */
    public List<String> scopeKeys(UUID postId) {
        List<String> keys = new ArrayList<>();
        keys.add(ScopeKeys.post(postId));
        jdbcTemplate.query("SELECT author_id FROM posts WHERE id = ?",
                rs -> {
                    keys.add(ScopeKeys.user(rs.getObject("author_id", UUID.class)));
                }, postId);
        jdbcTemplate.query("SELECT room_id FROM post_rooms WHERE post_id = ?",
                rs -> {
                    keys.add(ScopeKeys.room(rs.getObject("room_id", UUID.class)));
                }, postId);
        return keys;
    }

    /**
     * job 행을 id 오름차순(DB uuid 비교)으로 잠근다. 요청 job 과 verdict 의 활성 job 을 함께 잠글 때 교착을 피한다.
     * 없는 id 는 결과에 없다.
     */
    public List<LockedJob> lockJobs(Collection<UUID> jobIds) {
        UUID[] ids = jobIds.stream().distinct().toArray(UUID[]::new);
        if (ids.length == 0) {
            return List.of();
        }
        return jdbcTemplate.query(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    SELECT id, kind, aggregate_id, status, generation_id, deadline_at,
                           (status = 'RUNNING' AND lease_until > now()) AS lease_valid
                      FROM ai.jobs
                     WHERE id = ANY (?)
                     ORDER BY id
                       FOR UPDATE
                    """);
            ps.setArray(1, con.createArrayOf("uuid", ids));
            return ps;
        }, (rs, i) -> new LockedJob(
                rs.getObject("id", UUID.class),
                JobKind.valueOf(rs.getString("kind")),
                rs.getString("aggregate_id"),
                rs.getString("status"),
                rs.getObject("generation_id", UUID.class),
                rs.getObject("deadline_at", OffsetDateTime.class),
                rs.getBoolean("lease_valid")));
    }

    /** DB now(). 트랜잭션 시작 시각이라 한 트랜잭션 안에서는 같은 값이다. 마감 비교·예약 시각 계산에 쓴다. */
    public OffsetDateTime dbNow() {
        return jdbcTemplate.queryForObject("SELECT now()", OffsetDateTime.class);
    }

    /** 템플릿 {n}=표 수 합, {m}=유죄 표 수(AI 저장소 graphs/templates.py 와 같은 치환) */
    public JuryCounts juryCounts(UUID postId) {
        return jdbcTemplate.queryForObject("""
                SELECT count(*) AS jury, count(*) FILTER (WHERE verdict = CAST('guilty' AS verdict)) AS guilty
                  FROM votes
                 WHERE post_id = ?
                """, (rs, i) -> new JuryCounts(rs.getInt("jury"), rs.getInt("guilty")), postId);
    }
}
