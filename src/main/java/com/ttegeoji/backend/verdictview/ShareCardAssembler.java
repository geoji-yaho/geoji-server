package com.ttegeoji.backend.verdictview;

import com.ttegeoji.backend.privacy.VerdictReadGuard;
import com.ttegeoji.backend.privacy.VerdictReadGuard.Decision;
import com.ttegeoji.backend.verdict.TemplateCatalog;
import com.ttegeoji.backend.verdictview.VerdictViewQueries.EvidenceRef;
import com.ttegeoji.backend.verdictview.VerdictViewQueries.PostRow;
import com.ttegeoji.backend.verdictview.VerdictViewQueries.TextRow;
import com.ttegeoji.backend.verdictview.VerdictViewQueries.VerdictRow;
import com.ttegeoji.backend.verdictview.dto.ShareCardResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 10 §9 공유 카드. 공개 허용 문구와 이미지 metadata 만 낸다.
 * AI 문장은 인용한 근거가 전부 PUBLIC·무효화 전일 때만 남기고, 아니면 그 자리를 템플릿 문장으로 바꾼다.
 * 공유 방 밖으로 나가는 카드라 방 강도가 아니라 applied_intensity 행을 쓴다.
 */
@Component
@RequiredArgsConstructor
public class ShareCardAssembler {

    private final VerdictViewQueries queries;
    private final VerdictViewAssembler viewAssembler;
    private final VerdictReadGuard readGuard;
    private final SentenceLabels sentenceLabels;

    /**
     * @param roomIdOrNull 어느 방 판결로 카드를 만들지. 방마다 따로 재판하므로 필요하다.
     *                     없으면 옛 합산 판결만 찾는다(방별 재판 이전 게시물).
     */
    public ShareCardResponse assemble(UUID postId, UUID userId, UUID roomIdOrNull) {
        PostRow post = queries.findPost(postId)
                .filter(p -> !p.deleted())
                .orElseThrow(PublicApiRejection::notFound);
        if (!viewAssembler.canView(post, userId, roomIdOrNull)) {
            throw PublicApiRejection.notFound();
        }
        VerdictRow verdict = queries.findVerdict(postId, roomIdOrNull)
                .filter(v -> "FINAL".equals(v.sentenceStatus()) && !VerdictViewAssembler.DISMISSED.equals(v.juryResult()))
                .orElseThrow(() -> new PublicApiRejection(HttpStatus.NOT_FOUND, "판결이 아직 확정되지 않았습니다."));

        String intensity = verdict.appliedOrDefaultIntensity();
        TemplateCatalog.Rendered template = viewAssembler.renderTemplate(postId, verdict);
        Optional<TextRow> text = queries.findText(verdict.id(), intensity);

        String headline = template.headline();
        List<String> statement = template.statement();
        if (text.isPresent()) {
            Decision decision = readGuard.decide(postId, text.get().privacyEpochSnapshotJson());
            if (decision == Decision.BLOCKED) {
                throw PublicApiRejection.notFound();
            }
            if (decision == Decision.ORIGINAL && !VerdictViewAssembler.SOURCE_TEMPLATE.equals(text.get().source())) {
                List<String> ai = VerdictViewAssembler.statementTexts(text.get().statementJson());
                List<EvidenceRef> refs = queries.evidenceRefs(verdict.id(), intensity, text.get().textVersion());
                statement = mergePublic(ai, refs, template.statement());
                // 한 문장이라도 템플릿으로 바뀌면 AI 헤드라인이 가린 문장을 요약할 수 있어 템플릿 헤드라인으로
                headline = allPublic(ai.size(), refs) ? text.get().headline() : template.headline();
            }
        }

        return new ShareCardResponse(postId.toString(), post.postType(), verdict.juryResult(), intensity, headline,
                statement, verdict.sentence(), sentenceLabels.labelOf(verdict.sentence()), viewAssembler.meme(verdict));
    }

    /** 문장 j 의 인용이 하나라도 공개 불가면 템플릿 statement[j](넘치면 마지막 문장)로 바꾼다 */
    static List<String> mergePublic(List<String> ai, List<EvidenceRef> refs, List<String> template) {
        List<String> merged = new ArrayList<>(ai.size());
        for (int j = 0; j < ai.size(); j++) {
            if (sentencePublic(j, refs)) {
                merged.add(ai.get(j));
            } else {
                merged.add(template.get(Math.min(j, template.size() - 1)));
            }
        }
        return merged;
    }

    private static boolean allPublic(int sentences, List<EvidenceRef> refs) {
        for (int j = 0; j < sentences; j++) {
            if (!sentencePublic(j, refs)) {
                return false;
            }
        }
        return true;
    }

    private static boolean sentencePublic(int j, List<EvidenceRef> refs) {
        String path = "statement[" + j + "]";
        return refs.stream().filter(r -> path.equals(r.fieldPath())).allMatch(EvidenceRef::publicUsable);
    }
}
