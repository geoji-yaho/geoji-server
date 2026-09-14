package com.ttegeoji.backend.submission;

import com.ttegeoji.backend.ai.IntakeClient;
import com.ttegeoji.backend.ai.IntakeClient.IntakeRequest;
import com.ttegeoji.backend.ai.IntakeClient.IntakeResult;
import com.ttegeoji.backend.ai.IntakeClient.Mode;
import com.ttegeoji.backend.domain.Submission;
import com.ttegeoji.backend.domain.enums.IntakeStatus;
import com.ttegeoji.backend.domain.enums.SubmissionStatus;
import com.ttegeoji.backend.repository.SubmissionRepository;
import com.ttegeoji.backend.submission.dto.CompleteRequest;
import com.ttegeoji.backend.submission.dto.SubmissionResponse;
import com.ttegeoji.backend.submission.dto.SubmitRequest;
import com.ttegeoji.backend.util.Json;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 게시물 제출(10 §9·§4). 상태 NEW → NEEDS_INPUT → COMPLETED, BLOCKED. 최초 PASS 는 NEW → COMPLETED.
 * intake HTTP 는 DB 트랜잭션 밖에서 부른다(10 §2). 그래서 이 클래스는 @Transactional 이 아니고,
 * 상태 변경만 짧은 트랜잭션(TransactionTemplate·PostCreator)으로 나눈다.
 * 제출 만료(EXPIRED·만료 409)와 요청 한도 429 는 이번 범위 밖이다(9/14 결정).
 */
@Slf4j
@Service
public class SubmissionService {

    // AI API 422 중 입력 검증 결과(10 §4). 백엔드 검증과 같아 보통 오지 않는다
    private static final Set<String> INPUT_CODES = Set.of("ITEM_LENGTH", "REASON_LENGTH", "AMOUNT", "INVALID_REQUEST");

    private final IntakeClient intakeClient;
    private final PostCreator postCreator;
    private final SubmissionQueries queries;
    private final SubmissionRepository submissionRepository;
    private final EntityManager entityManager;
    private final TransactionTemplate tx;

    public SubmissionService(IntakeClient intakeClient, PostCreator postCreator, SubmissionQueries queries,
                             SubmissionRepository submissionRepository, EntityManager entityManager,
                             PlatformTransactionManager transactionManager) {
        this.intakeClient = intakeClient;
        this.postCreator = postCreator;
        this.queries = queries;
        this.submissionRepository = submissionRepository;
        this.entityManager = entityManager;
        this.tx = new TransactionTemplate(transactionManager);
    }

    public SubmissionResponse submit(UUID actorId, SubmitRequest request) {
        SubmissionPayload payload = SubmissionPayload.normalize(request.postType(), request.amountKrw(),
                request.category(), request.item(), request.reason(), request.roomIds());
        requireMembership(actorId, payload);

        Submission created = submissionRepository.save(Submission.builder()
                .actorId(actorId)
                .payloadHash(payload.hash())
                .build());
        UUID submissionId = created.getId();

        IntakeResult intake = callIntake(submissionId, payload, Mode.INITIAL);
        return switch (intake.status()) {
            case PASS -> completed(submissionId, postCreator.create(submissionId, payload, intake, IntakeStatus.PASS),
                    payload, intake);
            case NEEDS_CLARIFICATION -> update(submissionId, s -> {
                s.setStatus(SubmissionStatus.NEEDS_INPUT);
                s.setQuestionShown(true);
                s.setIntakeResult(Json.write(intake.toMap()));
            });
            case BLOCKED -> update(submissionId, s -> {
                s.setStatus(SubmissionStatus.BLOCKED);
                s.setIntakeResult(Json.write(intake.toMap()));
            });
        };
    }

    public SubmissionResponse complete(UUID actorId, UUID submissionId, CompleteRequest request) {
        Submission submission = submissionRepository.findByIdAndActorId(submissionId, actorId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "제출이 없습니다."));
        // 중복 완료는 기존 post 를 돌려준다(10 §13 작업 7)
        if (submission.getPostId() != null) {
            return response(submission);
        }
        if (request.action() == null) {
            throw new IllegalArgumentException("action 은 REVISE 또는 PROCEED 여야 합니다.");
        }
        requireRevision(submission, request.revision());
        SubmissionPayload payload = SubmissionPayload.normalize(request.postType(), request.amountKrw(),
                request.category(), request.item(), request.reason(), request.roomIds());
        requireMembership(actorId, payload);

        return request.action() == CompleteRequest.Action.PROCEED
                ? proceed(submission, payload)
                : revise(submission, payload, request.revision());
    }

    // 질문을 본 뒤 고치지 않고 등록. BLOCKED 는 우회할 수 없다(10 §9·§13 작업 7)
    private SubmissionResponse proceed(Submission submission, SubmissionPayload payload) {
        if (submission.getStatus() == SubmissionStatus.BLOCKED) {
            throw new IllegalStateException("차단된 제출은 그대로 등록할 수 없습니다.");
        }
        if (submission.getStatus() != SubmissionStatus.NEEDS_INPUT) {
            throw new IllegalStateException("지금은 완료할 수 없는 제출입니다.");
        }
        if (!payload.hash().equals(submission.getPayloadHash())) {
            throw new IllegalStateException("검토한 값과 다릅니다. 고친 값은 REVISE 로 보내 주세요.");
        }
        IntakeResult last = IntakeResult.fromJson(submission.getIntakeResult());
        UUID postId = postCreator.create(submission.getId(), payload, last, IntakeStatus.UNCLARIFIED);
        return completed(submission.getId(), postId, payload, last);
    }

    // 고친 값으로 FINAL_CHECK 1회(final_check_count). FINAL_CHECK 가 BLOCKED 면 더 고칠 수 없다
    private SubmissionResponse revise(Submission submission, SubmissionPayload payload, String revision) {
        requireRevisable(submission);
        // FINAL_CHECK 기회를 먼저 잠그고 커밋한다. 같은 revision 으로 동시에 온 REVISE 는 한 번만 intake 를 부른다
        tx.executeWithoutResult(status -> {
            Submission locked = lock(submission.getId());
            requireRevision(locked, revision);
            requireRevisable(locked);
            locked.setFinalCheckCount(locked.getFinalCheckCount() + 1);
            locked.setPayloadHash(payload.hash());
        });

        IntakeResult intake = callIntake(submission.getId(), payload, Mode.FINAL_CHECK);
        return switch (intake.status()) {
            case PASS -> completed(submission.getId(),
                    postCreator.create(submission.getId(), payload, intake, IntakeStatus.PASS), payload, intake);
            case BLOCKED -> update(submission.getId(), s -> {
                s.setStatus(SubmissionStatus.BLOCKED);
                s.setIntakeResult(Json.write(intake.toMap()));
            });
            // FINAL_CHECK 는 NEEDS_CLARIFICATION 을 내지 않는다(10 §4). 와도 질문은 제출당 1회라 다시 묻지 않고 등록한다
            case NEEDS_CLARIFICATION -> {
                log.warn("FINAL_CHECK 가 NEEDS_CLARIFICATION 을 냄 — 질문 없이 UNCLARIFIED 로 등록");
                yield completed(submission.getId(),
                        postCreator.create(submission.getId(), payload, intake, IntakeStatus.UNCLARIFIED),
                        payload, intake);
            }
        };
    }

    private static void requireRevisable(Submission submission) {
        SubmissionStatus status = submission.getStatus();
        if (status != SubmissionStatus.NEEDS_INPUT && status != SubmissionStatus.BLOCKED) {
            throw new IllegalStateException("지금은 고칠 수 없는 제출입니다.");
        }
        if (submission.getFinalCheckCount() >= 1) {
            throw new IllegalStateException("이미 한 번 고쳤습니다.");
        }
    }

    private static void requireRevision(Submission submission, String revision) {
        if (revision == null || !revision.equals(submission.getPayloadHash())) {
            throw new IllegalStateException("제출 내용이 바뀌었습니다. 다시 불러와 주세요.");
        }
    }

    private void requireMembership(UUID actorId, SubmissionPayload payload) {
        if (!queries.memberRoomIds(actorId, payload.roomIds()).containsAll(payload.roomIds())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "공유할 수 없는 방이 있습니다.");
        }
    }

    private IntakeResult callIntake(UUID submissionId, SubmissionPayload payload, Mode mode) {
        try {
            return intakeClient.call(new IntakeRequest(submissionId.toString(), payload.hash(), mode,
                    payload.postType(), payload.amountKrw(), payload.category(), payload.item(), payload.reason()));
        } catch (IntakeClient.RejectedException e) {
            if (INPUT_CODES.contains(e.getCode())) {
                throw new IllegalArgumentException("입력값이 올바르지 않습니다: " + e.getCode());
            }
            throw e;
        }
    }

    private Submission lock(UUID submissionId) {
        return Objects.requireNonNull(
                entityManager.find(Submission.class, submissionId, LockModeType.PESSIMISTIC_WRITE));
    }

    private SubmissionResponse update(UUID submissionId, Consumer<Submission> change) {
        return tx.execute(status -> {
            Submission locked = lock(submissionId);
            change.accept(locked);
            return response(locked);
        });
    }

    private static SubmissionResponse completed(UUID submissionId, UUID postId, SubmissionPayload payload,
                                                IntakeResult intake) {
        return new SubmissionResponse(submissionId, SubmissionStatus.COMPLETED, payload.hash(), intake.toMap(), postId);
    }

    @SuppressWarnings("unchecked")
    private static SubmissionResponse response(Submission submission) {
        Map<String, Object> intake = (Map<String, Object>) Json.read(submission.getIntakeResult());
        return new SubmissionResponse(submission.getId(), submission.getStatus(), submission.getPayloadHash(),
                intake, submission.getPostId());
    }
}
