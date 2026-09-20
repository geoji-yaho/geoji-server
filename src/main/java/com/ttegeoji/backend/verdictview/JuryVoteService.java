package com.ttegeoji.backend.verdictview;

import com.ttegeoji.backend.config.GeojiProperties;
import com.ttegeoji.backend.config.InternalApiException;
import com.ttegeoji.backend.jobs.JobKind;
import com.ttegeoji.backend.jobs.JobQueries;
import com.ttegeoji.backend.verdictview.VerdictViewQueries.LockedPostForVote;
import com.ttegeoji.backend.verdictview.dto.JuryVoteRequest;
import com.ttegeoji.backend.verdictview.dto.PostVoteRequest;
import com.ttegeoji.backend.verdictview.dto.PostVoteResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;

/**
 * 19 §5 떼거지봇 표. 워커가 JURY_VOTE job 하나당 한 번 부른다. 검증을 19 §5 순서(job → 봇 → 글 → 마감 → 중복 → 본문)로
 * 끝낸 뒤 {@link PostVoteService#cast} 를 그대로 탄다 — 사람 표와 같은 INSERT·정족수·평결 확정·D-24 게이트.
 * 게시물 잠금(FOR NO KEY UPDATE)을 먼저 잡아 검증과 cast 가 한 트랜잭션 안에서 같은 행을 본다.
 * 거부는 {@link InternalApiException} — 본문 {"code"} 하나(10 §4.7).
 */
@Service
@RequiredArgsConstructor
public class JuryVoteService {

    private final VerdictViewQueries queries;
    private final JobQueries jobQueries;
    private final PostVoteService voteService;
    private final GeojiProperties properties;

    @Transactional
    public PostVoteResponse cast(UUID postId, UUID generationId, JuryVoteRequest request) {
        // 1. job 존재 ∧ RUNNING ∧ X-Generation-Id(=본문 generation_id) 일치 ∧ lease 유효 ∧ JURY_VOTE
        if (generationId == null || !generationId.equals(request.generationId())) {
            throw reject(HttpStatus.CONFLICT, "STALE_GENERATION");
        }
        jobQueries.findRunningWithValidLease(request.jobId(), generationId)
                .filter(row -> row.kind() == JobKind.JURY_VOTE)
                .orElseThrow(() -> reject(HttpStatus.CONFLICT, "STALE_GENERATION"));
        // 2. voter_id == 떼거지봇. 설정이 비어 있어도 403 — 워커가 남의 표를 넣지 못하게
        UUID juror = properties.aiJuror().orElse(null);
        if (juror == null || !juror.equals(request.voterId())) {
            throw reject(HttpStatus.FORBIDDEN, "NOT_AI_JUROR");
        }
        // 3. 글 존재 ∧ 삭제 안 됨
        LockedPostForVote post = queries.lockPostForVote(postId)
                .filter(p -> !p.deleted())
                .orElseThrow(() -> reject(HttpStatus.NOT_FOUND, "NOT_FOUND"));
        // 4. 마감 전 ∧ 그 방 평결 미확정 ∧ 공유 철회 안 됨 ∧ 봇이 그 방 멤버. 작성자가 봇인 글도 표를 받지 않는다
        if (post.authorId().equals(juror)
                || post.deadlinePassed()
                || !queries.isMemberOfActiveSharedRoom(postId, request.roomId(), juror)
                || queries.verdictExists(postId, request.roomId())) {
            throw reject(HttpStatus.CONFLICT, "VOTING_CLOSED");
        }
        // 5. 아직 안 투표
        if (queries.voteExists(postId, juror, request.roomId())) {
            throw reject(HttpStatus.CONFLICT, "ALREADY_VOTED");
        }
        // 6. verdict 가 글 유형에 맞음 ∧ reason 1~500자(공백만은 안 됨)
        Set<String> allowed = PostVoteService.VERDICTS_BY_POST_TYPE.getOrDefault(post.postType(), Set.of());
        String reason = request.reason();
        if (request.verdict() == null || !allowed.contains(request.verdict())
                || reason == null || reason.isBlank()
                || reason.codePointCount(0, reason.length()) > PostVoteService.REASON_MAX) {
            throw reject(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_REQUEST");
        }
        // 7. 사람 표와 같은 경로. 위에서 다 걸렀으므로 여기서 거부되면 같은 트랜잭션 안의 상태 변화라 409 로 본다
        try {
            return voteService.cast(postId, juror, new PostVoteRequest(request.verdict(), reason, request.roomId()));
        } catch (PublicApiRejection e) {
            throw reject(HttpStatus.CONFLICT, "VOTING_CLOSED");
        }
    }

    private static InternalApiException reject(HttpStatus status, String code) {
        return new InternalApiException(status, code);
    }
}
