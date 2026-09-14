package com.ttegeoji.backend.schema;

import com.ttegeoji.backend.domain.enums.SpiceLevel;
import com.ttegeoji.backend.support.PostgresContainerSupport;
import com.ttegeoji.backend.verdict.CommitRecordRepository;
import com.ttegeoji.backend.verdict.CommitRecordRepository.CommitRecord;
import com.ttegeoji.backend.verdict.TextEvidenceRefRepository;
import com.ttegeoji.backend.verdict.TextEvidenceRefRepository.TextEvidenceRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 004 의 ai 테이블 2개를 repository SQL 로 실제 실행한다. 테스트마다 롤백
@SpringBootTest
@Transactional
class AiTables004Test extends PostgresContainerSupport {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private CommitRecordRepository commitRecords;
    @Autowired
    private TextEvidenceRefRepository evidenceRefs;

    @Test
    @DisplayName("10 §5 commit record 는 insert 뒤 find 로 같은 값이 나오고 같은 generation 두 번째 insert 는 거부")
    void commitRecordInsertAndFind() {
        UUID verdict = insertVerdict();
        UUID generation = UUID.randomUUID();

        CommitRecord inserted = commitRecords.insert(generation, "a".repeat(64), verdict, 3L);
        assertThat(inserted.committedAt()).isNotNull();
        assertThat(commitRecords.find(generation)).contains(inserted);
        assertThat(commitRecords.find(UUID.randomUUID())).isEmpty();

        assertThatThrownBy(() -> commitRecords.insert(generation, "b".repeat(64), verdict, 3L))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    @DisplayName("10 §8 text_evidence_refs 는 batch insert 뒤 evidence id 로 역조회된다")
    void textEvidenceRefsInsertAndFindByEvidence() {
        UUID verdict = insertVerdict();
        UUID dossier = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO ai.dossiers (id, post_id, snapshot_hash, label_map, privacy_versions)
                VALUES (?, 'p', 'h', '{}'::jsonb, '[]'::jsonb)""", dossier);
        UUID f0 = insertEvidence(dossier, "F0");
        UUID f1 = insertEvidence(dossier, "F1");
        UUID unused = insertEvidence(dossier, "F2");

        evidenceRefs.insertAll(List.of(
                new TextEvidenceRef(verdict, 1L, SpiceLevel.mild, "texts[0].statement[0]", f0),
                new TextEvidenceRef(verdict, 1L, SpiceLevel.hell, "texts[1].statement[0]", f0),
                new TextEvidenceRef(verdict, 1L, SpiceLevel.hell, "sentencing.sentencing_reason", f1)));
        evidenceRefs.insertAll(List.of());

        assertThat(evidenceRefs.findByEvidenceIds(List.of(f0)))
                .extracting(TextEvidenceRef::intensity).containsExactly(SpiceLevel.hell, SpiceLevel.mild);
        assertThat(evidenceRefs.findByEvidenceIds(List.of(f0, f1))).hasSize(3);
        assertThat(evidenceRefs.findByEvidenceIds(List.of(unused))).isEmpty();
        assertThat(evidenceRefs.findByEvidenceIds(List.of())).isEmpty();
    }

    private UUID insertVerdict() {
        UUID author = UUID.randomUUID();
        jdbc.update("INSERT INTO profiles (id, nickname, monthly_budget) VALUES (?, 'n', 0)", author);
        UUID post = jdbc.queryForObject("""
                INSERT INTO posts (author_id, post_type, amount_krw, category, item, intake_status, intake_source, vote_deadline_at)
                VALUES (?, 'spent', 5000, '식비', 'item', 'PASS', 'AI', now()) RETURNING id""", UUID.class, author);
        return jdbc.queryForObject("""
                INSERT INTO verdicts (post_id, jury_result, policy_snapshot, confirmed_at, target_intensities, default_intensity)
                VALUES (?, 'guilty', '{}'::jsonb, now(), '["mild","hell"]'::jsonb, 'mild') RETURNING id""", UUID.class, post);
    }

    private UUID insertEvidence(UUID dossier, String label) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO ai.evidence (id, dossier_id, label, epistemic_type, fact_type, text, scope)
                VALUES (?, ?, ?, 'DB_RECORD', 'SPEND', 't', '{}'::jsonb)""", id, dossier, label);
        return id;
    }
}
