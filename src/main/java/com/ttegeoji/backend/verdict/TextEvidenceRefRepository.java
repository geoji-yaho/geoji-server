package com.ttegeoji.backend.verdict;

import com.ttegeoji.backend.domain.enums.SpiceLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * ai.text_evidence_refs(10 §5 9단계, §8). 문구가 어떤 evidence 를 인용했는지.
 * 무효화 스케줄러는 evidence 로 역조회해 영향받는 verdict_texts 를 템플릿으로 바꾼다.
 */
@Repository
@RequiredArgsConstructor
public class TextEvidenceRefRepository {

    public record TextEvidenceRef(UUID verdictId, long textVersion, SpiceLevel intensity, String fieldPath,
                                  UUID evidenceId) {
    }

    private static final RowMapper<TextEvidenceRef> MAPPER = (rs, rowNum) -> new TextEvidenceRef(
            rs.getObject("verdict_id", UUID.class),
            rs.getLong("text_version"),
            SpiceLevel.valueOf(rs.getString("intensity")),
            rs.getString("field_path"),
            rs.getObject("evidence_id", UUID.class));

    private final JdbcTemplate jdbcTemplate;

    /** finalize 가 texts 와 같은 트랜잭션·같은 text_version 으로 넣는다 */
    public void insertAll(List<TextEvidenceRef> refs) {
        if (refs.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate("""
                        INSERT INTO ai.text_evidence_refs (verdict_id, text_version, intensity, field_path, evidence_id)
                        VALUES (?, ?, ?, ?, ?)""", refs, refs.size(),
                (ps, ref) -> {
                    ps.setObject(1, ref.verdictId());
                    ps.setLong(2, ref.textVersion());
                    ps.setString(3, ref.intensity().name());
                    ps.setString(4, ref.fieldPath());
                    ps.setObject(5, ref.evidenceId());
                });
    }

    public List<TextEvidenceRef> findByEvidenceIds(Collection<UUID> evidenceIds) {
        if (evidenceIds.isEmpty()) {
            return List.of();
        }
        return jdbcTemplate.query(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    SELECT verdict_id, text_version, intensity, field_path, evidence_id
                    FROM ai.text_evidence_refs WHERE evidence_id = ANY(?)
                    ORDER BY verdict_id, text_version, intensity, field_path""");
            ps.setArray(1, connection.createArrayOf("uuid", evidenceIds.toArray()));
            return ps;
        }, MAPPER);
    }
}
