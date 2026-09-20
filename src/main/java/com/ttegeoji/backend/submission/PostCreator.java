package com.ttegeoji.backend.submission;

import com.ttegeoji.backend.ai.IntakeClient.IntakeResult;
import com.ttegeoji.backend.config.GeojiProperties;
import com.ttegeoji.backend.domain.Post;
import com.ttegeoji.backend.domain.PostRoom;
import com.ttegeoji.backend.domain.Submission;
import com.ttegeoji.backend.domain.enums.IntakeStatus;
import com.ttegeoji.backend.domain.enums.SubmissionStatus;
import com.ttegeoji.backend.jobs.JobEnqueuer;
import com.ttegeoji.backend.repository.PostRepository;
import com.ttegeoji.backend.repository.PostRoomRepository;
import com.ttegeoji.backend.util.Json;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

/**
 * 게시물 저장 업무 트랜잭션(10 §3·§9). posts + post_rooms + PREPARE job + JURY_VOTE job(19 §3) + submission COMPLETED 를
 * 한 트랜잭션으로.
 * intake HTTP 는 이 트랜잭션 전에 끝나 있어야 한다(10 §2). 이 트랜잭션은 privacy scope·verdict 행을 잠그지 않는다.
 */
@Component
@RequiredArgsConstructor
public class PostCreator {

    private final EntityManager entityManager;
    private final PostRepository postRepository;
    private final PostRoomRepository postRoomRepository;
    private final JobEnqueuer jobEnqueuer;
    private final SubmissionQueries queries;
    private final GeojiProperties properties;

    /**
     * submission 행을 잠그고 이미 게시물이 있으면 그 id 를 돌려준다(중복 완료, 10 §13 작업 7).
     *
     * @return 만든(또는 기존) post_id
     */
    @Transactional
    public UUID create(UUID submissionId, SubmissionPayload payload, IntakeResult intake, IntakeStatus intakeStatus) {
        Submission submission = entityManager.find(Submission.class, submissionId, LockModeType.PESSIMISTIC_WRITE);
        if (submission == null) {
            throw new IllegalArgumentException("제출이 없습니다.");
        }
        if (submission.getPostId() != null) {
            return submission.getPostId();
        }

        OffsetDateTime voteDeadlineAt = queries.voteDeadlineAt(payload.roomIds())
                .orElseThrow(() -> new IllegalArgumentException("공유할 방이 없습니다."));
        Post post = postRepository.saveAndFlush(Post.builder()
                .authorId(submission.getActorId())
                .postType(payload.postType())
                .amountKrw(payload.amountKrw())
                .category(payload.category())
                .item(payload.item())
                .reason(payload.reason())
                .intakeStatus(intakeStatus)
                .intakeSource(intake.intakeSource())
                .submissionId(submissionId)
                .voteDeadlineAt(voteDeadlineAt)
                .build());
        postRoomRepository.saveAll(payload.roomIds().stream().map(roomId -> PostRoom.of(post.getId(), roomId)).toList());
        postRoomRepository.flush();

        // post_type 은 spent·considering 두 값뿐이라 둘 다 PREPARE 대상이다. NO_SPEND 는 posts 에 오지 않는다(10 §3)
        jobEnqueuer.enqueuePrepare(post.getId().toString(), post.getVersion(), post.getAudienceVersion());
        enqueueJuryVotes(post, payload);

        submission.setStatus(SubmissionStatus.COMPLETED);
        submission.setPayloadHash(payload.hash());
        submission.setPostId(post.getId());
        submission.setIntakeResult(Json.write(intake.toMap()));
        entityManager.flush();
        return post.getId();
    }

    /**
     * 19 §3 떼거지봇이 멤버이고 작성자가 봇이 아닌 공유 방마다 JURY_VOTE 1개. 봇 미설정이면 없다.
     * 봇 글(템플릿 2개)에는 만들지 않는다 — 자기 글에 투표하지 않는다(19 §1).
     */
    private void enqueueJuryVotes(Post post, SubmissionPayload payload) {
        UUID juror = properties.aiJuror().orElse(null);
        if (juror == null || juror.equals(post.getAuthorId())) {
            return;
        }
        Set<UUID> jurorRooms = queries.memberRoomIds(juror, payload.roomIds());
        for (UUID roomId : payload.roomIds()) {
            if (jurorRooms.contains(roomId)) {
                jobEnqueuer.enqueueJuryVote(post.getId().toString(), post.getVersion(), roomId.toString(),
                        juror.toString());
            }
        }
    }
}
