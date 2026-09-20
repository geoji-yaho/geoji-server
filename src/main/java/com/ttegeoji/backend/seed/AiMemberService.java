package com.ttegeoji.backend.seed;

import com.ttegeoji.backend.config.GeojiProperties;
import com.ttegeoji.backend.domain.Profile;
import com.ttegeoji.backend.domain.enums.PostType;
import com.ttegeoji.backend.domain.enums.SubmissionStatus;
import com.ttegeoji.backend.repository.ProfileRepository;
import com.ttegeoji.backend.repository.RoomMemberRepository;
import com.ttegeoji.backend.repository.RoomRepository;
import com.ttegeoji.backend.submission.SubmissionService;
import com.ttegeoji.backend.submission.dto.CompleteRequest;
import com.ttegeoji.backend.submission.dto.SubmissionResponse;
import com.ttegeoji.backend.submission.dto.SubmitRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 19 §6 '데모용 AI 유저 추가'. 떼거지봇을 방 멤버로 넣고 봇 명의 템플릿 글 2개(19 §7)를 그 방에 올린다.
 * 글은 SubmissionService(실제 intake·PREPARE job)를 거친다 — 시드 러너 findOrSubmit 과 같다. intake HTTP 가 있어
 * 이 클래스는 @Transactional 이 아니다(10 §2). 멱등: 멤버·글이 이미 있으면 건너뛰고 200, 하나라도 새로 만들었으면 201.
 * 봇 글에는 JURY_VOTE 가 생기지 않는다(PostCreator 가 작성자==봇을 뺀다).
 * 봇은 방 3개 상한(참가 API)의 대상이 아니다 — room_members 에 직접 넣는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiMemberService {

    public static final String NICKNAME = "떼거지봇";

    /** 19 §7 템플릿 글 2개. 시드(SeedData)의 심야 택시·무선 이어폰과 같은 값 */
    static final List<TemplatePost> TEMPLATES = List.of(
            new TemplatePost(PostType.spent, 32_000, "교통/택시", "심야 택시", "막차가 끊겨서 어쩔 수 없었어요"),
            new TemplatePost(PostType.considering, 189_000, "쇼핑/패션", "무선 이어폰", "기존 이어폰 한쪽이 안 들려요"));

    record TemplatePost(PostType postType, int amountKrw, String category, String item, String reason) {
    }

    /** @param created 멤버 또는 글을 하나라도 새로 만들었나(201/200) */
    public record Result(UUID userId, String nickname, List<UUID> postIds, boolean created) {
    }

    /** GEOJI_AI_JUROR_USER_ID 가 비었거나 그 profiles 행이 없다 → 503 AI_JUROR_NOT_CONFIGURED */
    public static class NotConfiguredException extends RuntimeException {
        NotConfiguredException() {
            super("AI_JUROR_NOT_CONFIGURED");
        }
    }

    /** 방이 없거나 삭제됐거나 요청자가 멤버가 아니다 → 404. 방이 있는지 드러내지 않는다(GET /api/rooms/{id} 규칙) */
    public static class RoomNotFoundException extends RuntimeException {
        RoomNotFoundException() {
            super("방을 찾을 수 없습니다.");
        }
    }

    private final GeojiProperties properties;
    private final ProfileRepository profileRepository;
    private final RoomRepository roomRepository;
    private final RoomMemberRepository roomMemberRepository;
    private final AiMemberQueries queries;
    private final SubmissionService submissionService;

    public Result add(UUID roomId, UUID requesterId) {
        UUID juror = properties.aiJuror().orElseThrow(NotConfiguredException::new);
        Profile profile = profileRepository.findById(juror).orElseThrow(NotConfiguredException::new);
        if (roomRepository.findByIdAndDeletedAtIsNull(roomId).isEmpty()
                || !roomMemberRepository.existsById_RoomIdAndId_UserId(roomId, requesterId)) {
            throw new RoomNotFoundException();
        }

        boolean created = queries.insertMemberIfAbsent(roomId, juror);
        List<UUID> postIds = new ArrayList<>();
        for (TemplatePost template : TEMPLATES) {
            Optional<UUID> existing = queries.findPosts(juror, roomId, template.postType().name(),
                    template.amountKrw(), template.category(), template.item(), template.reason()).stream().findFirst();
            if (existing.isPresent()) {
                postIds.add(existing.get());
                continue;
            }
            postIds.add(submit(juror, roomId, template));
            created = true;
        }
        String nickname = profile.getNickname() != null ? profile.getNickname() : NICKNAME;
        return new Result(juror, nickname, List.copyOf(postIds), created);
    }

    // 시드 러너 findOrSubmit 과 같다. 질문(NEEDS_INPUT)이 나와도 템플릿 값 그대로 PROCEED(19 §7)
    private UUID submit(UUID juror, UUID roomId, TemplatePost t) {
        List<UUID> roomIds = List.of(roomId);
        SubmissionResponse submitted = submissionService.submit(juror, new SubmitRequest(t.postType(),
                t.amountKrw(), t.category(), t.item(), t.reason(), roomIds));
        if (submitted.status() == SubmissionStatus.NEEDS_INPUT) {
            submitted = submissionService.complete(juror, submitted.submissionId(), new CompleteRequest(
                    CompleteRequest.Action.PROCEED, submitted.revision(), t.postType(), t.amountKrw(),
                    t.category(), t.item(), t.reason(), roomIds));
        }
        if (submitted.status() != SubmissionStatus.COMPLETED || submitted.postId() == null) {
            log.error("19 §7 떼거지봇 템플릿 글 등록 실패 item={} status={}", t.item(), submitted.status());
            throw new IllegalStateException("떼거지봇 템플릿 글을 등록하지 못했습니다.");
        }
        return submitted.postId();
    }
}
