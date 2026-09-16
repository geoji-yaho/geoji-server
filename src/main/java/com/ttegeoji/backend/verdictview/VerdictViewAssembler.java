package com.ttegeoji.backend.verdictview;

import com.ttegeoji.backend.privacy.VerdictReadGuard;
import com.ttegeoji.backend.privacy.VerdictReadGuard.Decision;
import com.ttegeoji.backend.util.Json;
import com.ttegeoji.backend.verdict.GenerationQueries;
import com.ttegeoji.backend.verdict.TemplateCatalog;
import com.ttegeoji.backend.verdictview.VerdictViewQueries.PostRow;
import com.ttegeoji.backend.verdictview.VerdictViewQueries.TextRow;
import com.ttegeoji.backend.verdictview.VerdictViewQueries.VerdictRow;
import com.ttegeoji.backend.verdictview.dto.MemeView;
import com.ttegeoji.backend.verdictview.dto.VerdictTextView;
import com.ttegeoji.backend.verdictview.dto.VerdictViewResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 10 §9 판결 조회(verdict-view-v1, camelCase) 조립과 §8 읽기 차단.
 * 없음·권한 없음·삭제는 모두 같은 404 라 존재 여부가 드러나지 않는다.
 */
@Component
@RequiredArgsConstructor
public class VerdictViewAssembler {

    static final int SCHEMA_VERSION = 1;
    // 폴링 권장값(사용자 9/15). 투표 중은 표가 모일 때까지 느리게, 생성 중은 1초, 템플릿 뒤 30초, AI 완료·각하는 중단
    static final int POLL_VOTING_MS = 5000;
    static final int POLL_GENERATING_MS = 1000;
    static final int POLL_TEMPLATE_READY_MS = 30000;
    static final int POLL_STOP_MS = 0;

    static final String SOURCE_TEMPLATE = "TEMPLATE";
    static final String DISMISSED = "dismissed";

    private final VerdictViewQueries queries;
    private final VerdictReadGuard readGuard;
    private final TemplateCatalog templateCatalog;
    private final GenerationQueries generationQueries;
    private final SentenceLabels sentenceLabels;

    public VerdictViewResponse assemble(UUID postId, UUID userId, UUID roomIdOrNull) {
        PostRow post = queries.findPost(postId)
                .filter(p -> !p.deleted())
                .orElseThrow(PublicApiRejection::notFound);
        if (!canView(post, userId, roomIdOrNull)) {
            throw PublicApiRejection.notFound();
        }

        Optional<VerdictRow> found = queries.findVerdict(postId, roomIdOrNull);
        if (found.isEmpty()) {
            return new VerdictViewResponse(SCHEMA_VERSION, postId.toString(), null, "PENDING", "PENDING", 0L, null,
                    POLL_VOTING_MS);
        }
        VerdictRow verdict = found.get();
        if (DISMISSED.equals(verdict.juryResult())) {
            return response(postId, verdict, null, POLL_STOP_MS);
        }

        int poll = pollAfterMs(verdict.textStatus());
        if ("PENDING".equals(verdict.textStatus()) || "GENERATING".equals(verdict.textStatus())) {
            return response(postId, verdict, null, poll);
        }
        // 방별 판결은 강도가 그 방 강도 하나뿐이라 applied_intensity 가 곧 그 방 강도다.
        // 옛 합산 판결만 room_id 로 강도를 갈아 끼운다(그때는 한 판결을 여러 방이 나눠 봤다)
        String intensity = verdict.roomId() != null || roomIdOrNull == null
                ? verdict.appliedOrDefaultIntensity()
                : queries.roomSpiceLevel(roomIdOrNull).orElseThrow(PublicApiRejection::notFound);
        Optional<TextRow> text = queries.findText(verdict.id(), intensity);
        if (text.isEmpty()) {
            return response(postId, verdict, null, poll);
        }

        Decision decision = readGuard.decide(postId, text.get().privacyEpochSnapshotJson());
        VerdictTextView view = switch (decision) {
            case BLOCKED -> throw PublicApiRejection.notFound();
            case TEMPLATE -> templateView(postId, verdict, intensity);
            case ORIGINAL -> storedView(verdict, intensity, text.get());
        };
        return response(postId, verdict, view, poll);
    }

    /** 작성자이거나 철회 안 된 공유 방 멤버. room_id 가 오면 그 방이 철회 안 된 공유 방이어야 한다 */
    boolean canView(PostRow post, UUID userId, UUID roomIdOrNull) {
        boolean author = post.authorId().equals(userId);
        if (roomIdOrNull == null) {
            return author || queries.isMemberOfAnyActiveSharedRoom(post.id(), userId);
        }
        if (author) {
            return queries.isActiveSharedRoom(post.id(), roomIdOrNull);
        }
        return queries.isMemberOfActiveSharedRoom(post.id(), roomIdOrNull, userId);
    }

    static int pollAfterMs(String textStatus) {
        return switch (textStatus) {
            case "TEMPLATE_READY" -> POLL_TEMPLATE_READY_MS;
            case "AI_READY" -> POLL_STOP_MS;
            default -> POLL_GENERATING_MS;
        };
    }

    TemplateCatalog.Rendered renderTemplate(UUID postId, VerdictRow verdict) {
        GenerationQueries.JuryCounts counts = generationQueries.juryCounts(postId, verdict.roomId());
        return templateCatalog.render(verdict.juryResult(), counts.juryCount(), counts.guiltyCount(), verdict.sentence());
    }

    MemeView meme(VerdictRow verdict) {
        return verdict.memeImageId() == null
                ? null
                : new MemeView(verdict.memeTag(), verdict.memeImageId().toString(), verdict.memeImageUrl());
    }

    /** verdict_texts.statement jsonb [{text, kind, evidence_labels}] 에서 text 만 */
    static List<String> statementTexts(String statementJson) {
        if (!(Json.read(statementJson) instanceof List<?> items)) {
            throw new IllegalStateException("verdict_texts.statement 가 배열이 아닙니다.");
        }
        return items.stream()
                .map(item -> item instanceof Map<?, ?> m ? m.get("text") : null)
                .map(value -> {
                    if (!(value instanceof String s)) {
                        throw new IllegalStateException("verdict_texts.statement 원소에 text 가 없습니다.");
                    }
                    return s;
                })
                .toList();
    }

    // 10 §9: source=TEMPLATE 이면 양형 이유 블록을 숨긴다
    private VerdictTextView templateView(UUID postId, VerdictRow verdict, String intensity) {
        TemplateCatalog.Rendered rendered = renderTemplate(postId, verdict);
        return new VerdictTextView(intensity, rendered.headline(), rendered.statement(), verdict.sentence(),
                sentenceLabels.labelOf(verdict.sentence()), null, SOURCE_TEMPLATE, meme(verdict));
    }

    private VerdictTextView storedView(VerdictRow verdict, String intensity, TextRow text) {
        boolean template = SOURCE_TEMPLATE.equals(text.source());
        return new VerdictTextView(intensity, text.headline(), statementTexts(text.statementJson()), verdict.sentence(),
                sentenceLabels.labelOf(verdict.sentence()), template ? null : verdict.sentencingReason(),
                text.source(), meme(verdict));
    }

    private static VerdictViewResponse response(UUID postId, VerdictRow verdict, VerdictTextView view, int poll) {
        return new VerdictViewResponse(SCHEMA_VERSION, postId.toString(), verdict.juryResult(), verdict.sentenceStatus(),
                verdict.textStatus(), verdict.textVersion(), view, poll);
    }
}
