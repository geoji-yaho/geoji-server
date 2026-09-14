package com.ttegeoji.backend.verdict;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * ai.verdict_commit_records(10 §5 1·3·12단계). generation 하나에 1행.
 * 같은 generation 재도착은 request_hash 로 이전 성공 응답인지 IDEMPOTENCY_CONFLICT 인지 가른다.
 */
@Repository
@RequiredArgsConstructor
public class CommitRecordRepository {

    public record CommitRecord(UUID generationId, String requestHash, UUID verdictId, long textVersion,
                               OffsetDateTime committedAt) {
    }

    private static final RowMapper<CommitRecord> MAPPER = (rs, rowNum) -> new CommitRecord(
            rs.getObject("generation_id", UUID.class),
            rs.getString("request_hash"),
            rs.getObject("verdict_id", UUID.class),
            rs.getLong("text_version"),
            rs.getObject("committed_at", OffsetDateTime.class));

    private final JdbcTemplate jdbcTemplate;

    public Optional<CommitRecord> find(UUID generationId) {
        return jdbcTemplate.query("""
                        SELECT generation_id, request_hash, verdict_id, text_version, committed_at
                        FROM ai.verdict_commit_records WHERE generation_id = ?""", MAPPER, generationId)
                .stream().findFirst();
    }

    /** committed_at 은 DB now(). 이미 있으면 DuplicateKeyException — 3단계 재확인이 잠금 아래에서 막는다 */
    public CommitRecord insert(UUID generationId, String requestHash, UUID verdictId, long textVersion) {
        return jdbcTemplate.queryForObject("""
                        INSERT INTO ai.verdict_commit_records (generation_id, request_hash, verdict_id, text_version)
                        VALUES (?, ?, ?, ?)
                        RETURNING generation_id, request_hash, verdict_id, text_version, committed_at""",
                MAPPER, generationId, requestHash, verdictId, textVersion);
    }
}
