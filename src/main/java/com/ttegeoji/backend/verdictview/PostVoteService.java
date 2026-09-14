package com.ttegeoji.backend.verdictview;

import com.ttegeoji.backend.verdict.VerdictConfirmationService;
import com.ttegeoji.backend.verdictview.VerdictViewQueries.InsertedVote;
import com.ttegeoji.backend.verdictview.VerdictViewQueries.LockedPostForVote;
import com.ttegeoji.backend.verdictview.dto.PostVoteRequest;
import com.ttegeoji.backend.verdictview.dto.PostVoteResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * SPEC S-14 배심원 투표(10 에 없음). 게시물 잠금 → 검증 → 표 INSERT → 평결 확정 시도가 한 트랜잭션이다.
 * 검증 순서 404 → 403 → 400 → 409 는 존재·권한을 입력 오류보다 먼저 숨기려는 것이다.
 */
@Service
@RequiredArgsConstructor
public class PostVoteService {

    static final int REASON_MAX = 500;

    private static final Map<String, Set<String>> VERDICTS_BY_POST_TYPE = Map.of(
            "spent", Set.of("guilty", "notGuilty"),
            "considering", Set.of("agree", "disagree"));

    private final VerdictViewQueries queries;
    private final VerdictConfirmationService confirmationService;

    @Transactional
    public PostVoteResponse cast(UUID postId, UUID voterId, PostVoteRequest request) {
        LockedPostForVote post = queries.lockPostForVote(postId)
                .filter(p -> !p.deleted())
                .orElseThrow(PublicApiRejection::notFound);
        if (post.authorId().equals(voterId)) {
            throw new PublicApiRejection(HttpStatus.FORBIDDEN, "본인 게시물에는 투표할 수 없습니다.");
        }
        if (request.roomId() == null || !queries.isMemberOfActiveSharedRoom(postId, request.roomId(), voterId)) {
            throw new PublicApiRejection(HttpStatus.FORBIDDEN, "이 방에서는 투표할 수 없습니다.");
        }
        // Set.of 의 contains(null) 은 NPE 라 null 을 먼저 거른다
        if (request.verdict() == null
                || !VERDICTS_BY_POST_TYPE.getOrDefault(post.postType(), Set.of()).contains(request.verdict())) {
            throw new PublicApiRejection(HttpStatus.BAD_REQUEST, "이 게시물에 맞지 않는 평결입니다.");
        }
        String reason = request.reason();
        if (reason == null || reason.isBlank()) {
            throw new PublicApiRejection(HttpStatus.BAD_REQUEST, "투표 사유를 입력해 주세요.");
        }
        // DB CHECK 는 char_length(코드 포인트) 기준이다
        if (reason.codePointCount(0, reason.length()) > REASON_MAX) {
            throw new PublicApiRejection(HttpStatus.BAD_REQUEST, "투표 사유는 500자 이하여야 합니다.");
        }
        if (post.deadlinePassed() || queries.verdictExists(postId)) {
            throw new PublicApiRejection(HttpStatus.CONFLICT, "투표가 마감되었습니다.");
        }
        if (queries.voteExists(postId, voterId)) {
            throw new PublicApiRejection(HttpStatus.CONFLICT, "이미 투표했습니다.");
        }

        InsertedVote vote = queries.insertVote(postId, voterId, request.roomId(), request.verdict(), reason);
        confirmationService.onVoteCast(postId);
        return new PostVoteResponse(vote.id().toString(), postId.toString(), request.roomId().toString(),
                request.verdict(), reason, vote.createdAt());
    }
}
