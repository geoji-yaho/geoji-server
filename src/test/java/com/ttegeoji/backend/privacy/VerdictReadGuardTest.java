package com.ttegeoji.backend.privacy;

import com.ttegeoji.backend.privacy.InvalidationServiceTest.Fixtures;
import com.ttegeoji.backend.privacy.VerdictReadGuard.Decision;
import com.ttegeoji.backend.support.PostgresContainerSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// 실제로 커밋한다. 테스트마다 새 게시물·scope 라 섞이지 않는다
@SpringBootTest
class VerdictReadGuardTest extends PostgresContainerSupport {

    @Autowired
    private VerdictReadGuard guard;
    @Autowired
    private InvalidationService service;
    @Autowired
    private PrivacyEpochRepository epochs;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TransactionTemplate tx;

    private Fixtures f;
    private UUID author;
    private UUID room;
    private UUID post;

    @BeforeEach
    void setUp() {
        f = new Fixtures(jdbc);
        author = f.profile();
        room = f.room(author);
        post = f.post(author);
        f.share(post, room);
        tx.executeWithoutResult(s -> epochs.bump(ScopeKeys.post(post)));
    }

    @Test
    @DisplayName("10 §8 3문단 snapshot 의 모든 scope epoch == 현재(행 없음 0 포함) → 원문 허용")
    void sameEpochsAllowOriginal() {
        assertThat(guard.decide(post, snapshot(1, 0, 0))).isEqualTo(Decision.ORIGINAL);
    }

    @Test
    @DisplayName("10 §8 3문단·§13 작업 8 공유 철회로 post epoch 이 바뀌면 → 템플릿")
    void withdrawnShareMeansTemplate() {
        service.withdrawRoomShare(post, room);

        assertThat(guard.decide(post, snapshot(1, 0, 0))).isEqualTo(Decision.TEMPLATE);
    }

    @Test
    @DisplayName("10 §8 3문단 한 scope 라도 다르면(room epoch +1) → 템플릿")
    void anyScopeDifferentMeansTemplate() {
        tx.executeWithoutResult(s -> epochs.bump(ScopeKeys.room(room)));

        assertThat(guard.decide(post, snapshot(1, 0, 0))).isEqualTo(Decision.TEMPLATE);
        assertThat(guard.decide(post, snapshot(1, 1, 0))).isEqualTo(Decision.ORIGINAL);
    }

    @Test
    @DisplayName("10 §8 사건 삭제 → 스케줄러가 돌기 전에도 즉시 차단, epoch 비교보다 우선")
    void deletedPostBlocked() {
        service.deletePost(post, author);

        // 삭제 뒤 epoch(2)와 같은 snapshot 이어도 차단
        assertThat(guard.decide(post, snapshot(2, 0, 0))).isEqualTo(Decision.BLOCKED);
        assertThat(guard.decide(post, snapshot(1, 0, 0))).isEqualTo(Decision.BLOCKED);
        assertThat(guard.decide(post, null)).isEqualTo(Decision.BLOCKED);
    }

    @Test
    @DisplayName("10 §8 snapshot NULL(템플릿 저장 행) → epoch 가 바뀌어도 원문 허용")
    void templateRowWithoutSnapshotAllowed() {
        service.withdrawRoomShare(post, room);

        assertThat(guard.decide(post, null)).isEqualTo(Decision.ORIGINAL);
    }

    private String snapshot(long postEpoch, long roomEpoch, long userEpoch) {
        return """
                [{"scope_key": "%s", "epoch": %d}, {"scope_key": "%s", "epoch": %d}, {"scope_key": "%s", "epoch": %d}]"""
                .formatted(ScopeKeys.post(post), postEpoch, ScopeKeys.room(room), roomEpoch,
                        ScopeKeys.user(author), userEpoch);
    }
}
