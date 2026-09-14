package com.ttegeoji.backend.privacy;

import com.ttegeoji.backend.jobs.JobQueries;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * 10 §8 삭제·권한 변경. 한 트랜잭션에서 scope epoch 잠금·증가 → 원본 비활성화 → 진행 중 job 끄기(D-26) → 무효화 작업 기록.
 * 파생 정리(invalidate_scope.sql·템플릿 전환)는 InvalidationScheduler 가 비동기로 하고, 읽기 차단은 epoch 로 즉시 된다.
 * 잠금 순서는 privacy scope(key 오름차순) → posts·post_rooms 행 → job 행(10 §2).
 * 탈퇴(user:{id})는 10 §14 "D-26 탈퇴 범위" 미결이라 두지 않는다.
 */
@Service
@RequiredArgsConstructor
public class InvalidationService {

    static final String POST_SOURCE_TYPE = "POST";

    record SourceRef(String type, String id) {
    }

    private final PrivacyEpochRepository epochs;
    private final JobQueries jobQueries;
    private final InvalidationQueries queries;

    /** 작성자 본인만. 아니면 IllegalStateException(서버 "본인 것만" 관례) */
    @Transactional
    public void deletePost(UUID postId, UUID actorId) {
        if (!queries.isPostAuthor(postId, actorId)) {
            throw new IllegalStateException("본인 게시물만 삭제할 수 있습니다.");
        }
        // 이미 삭제됐으면 epoch·기록을 더 올리지 않고 성공(사용자 9/15)
        if (queries.isPostDeleted(postId)) {
            return;
        }
        // source 조합은 AI 통합 테스트(test_deletion.py)가 쓰는 ('POST', post_id, 'post:{id}')
        invalidate(List.of(ScopeKeys.post(postId)), POST_SOURCE_TYPE, postId.toString(), () -> {
            queries.markPostDeleted(postId);
            cancelActiveJobs(postId);
        });
    }

    /** 방 공유 철회. backend 에 DELETE 권한이 없어 post_rooms 행은 revoked_at 표시로 남긴다(004b) */
    @Transactional
    public void withdrawRoomShare(UUID postId, UUID roomId) {
        SourceRef source = roomShareSource(postId, roomId);
        invalidate(List.of(ScopeKeys.post(postId)), source.type(), source.id(), () -> {
            queries.revokeRoomShare(postId, roomId);
            queries.bumpAudienceVersion(postId);
            cancelActiveJobs(postId);
        });
    }

    /** 일반형. 원본 비활성화는 호출자가 같은 트랜잭션에서 한다(댓글 삭제 등) */
    @Transactional
    public void invalidate(Collection<String> scopeKeys, String sourceType, String sourceId) {
        invalidate(scopeKeys, sourceType, sourceId, () -> {
        });
    }

    private void invalidate(Collection<String> scopeKeys, String sourceType, String sourceId, Runnable deactivateSource) {
        // lockAndRead 와 같은 정렬이라 bump·기록도 잠금 순서를 따른다
        List<String> keys = scopeKeys.stream().distinct().sorted().toList();
        if (keys.isEmpty()) {
            throw new IllegalArgumentException("무효화할 scope 가 없습니다.");
        }
        epochs.lockAndRead(keys);
        keys.forEach(epochs::bump);
        deactivateSource.run();
        keys.forEach(key -> queries.insertPending(key, sourceType, sourceId));
    }

    private void cancelActiveJobs(UUID postId) {
        jobQueries.cancelActiveForPost(postId.toString(), queries.findVerdictIds(postId));
    }

    // 게시물 삭제와 같은 ('POST', post_id). 철회도 그 게시물 파생 전부를 무효화한다(AI test_deletion.py 선례, 사용자 9/15)
    static SourceRef roomShareSource(UUID postId, UUID roomId) {
        return new SourceRef(POST_SOURCE_TYPE, postId.toString());
    }
}
