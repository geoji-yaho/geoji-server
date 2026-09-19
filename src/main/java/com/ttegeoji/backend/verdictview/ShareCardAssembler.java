package com.ttegeoji.backend.verdictview;

import com.ttegeoji.backend.privacy.VerdictReadGuard;
import com.ttegeoji.backend.privacy.VerdictReadGuard.Decision;
import com.ttegeoji.backend.verdict.TemplateCatalog;
import com.ttegeoji.backend.verdictview.VerdictViewQueries.PostRow;
import com.ttegeoji.backend.verdictview.VerdictViewQueries.TextRow;
import com.ttegeoji.backend.verdictview.VerdictViewQueries.VerdictRow;
import com.ttegeoji.backend.verdictview.dto.ShareCardResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

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
     *
     * <p>9/19 사용자 결정: 카드에도 AI 제목·본문을 그대로 쓴다. 전에는 본문이 인용한 근거 중
     * 공개 불가가 하나라도 있으면 본문과 제목을 템플릿으로 내렸는데, 좋은 판결문일수록 방 규칙과
     * 과거 지출을 인용해서 거의 모든 카드가 "유죄 / 배심원단이 …" 로만 나왔다.
     * <b>카드를 방 밖으로 공유하면 방 규칙이 함께 나갈 수 있다.</b> 그것을 감수한 선택이다.
     * 삭제·철회 뒤 읽기 차단(BLOCKED)은 그대로 둔다 — 그건 근거 공개 여부가 아니라 무효화다.
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
                statement = VerdictViewAssembler.statementTexts(text.get().statementJson());
                headline = text.get().headline();
            }
        }

        return new ShareCardResponse(postId.toString(), post.postType(), verdict.juryResult(), intensity, headline,
                statement, verdict.sentence(), sentenceLabels.labelOf(verdict.sentence()), viewAssembler.meme(verdict));
    }

}
