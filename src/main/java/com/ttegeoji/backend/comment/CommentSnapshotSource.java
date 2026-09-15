package com.ttegeoji.backend.comment;

import com.ttegeoji.backend.internal.CommentSource;
import com.ttegeoji.backend.internal.dto.CommentSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.UUID;

/**
 * 10 §4.1 RETAIN comment.approved 원본 댓글. 댓글 삭제·게시물 삭제·댓글 방 공유 철회면 empty → 조립기가 404.
 * post_status 는 JUDGED 만 쓴다. JUDGED 가 아니면 새 값을 만들지 않고 empty 다(게이트 결정 B-1).
 * id 가 null·UUID 아님이어도 예외 없이 empty(조립기가 500 을 내지 않게).
 */
@Component
@RequiredArgsConstructor
public class CommentSnapshotSource implements CommentSource {

    // AI domain/comment_safety.py 가 비교하는 값
    static final String JUDGED = "JUDGED";

    private final CommentQueries queries;

    @Override
    @Transactional(readOnly = true)
    public Optional<CommentSnapshot> find(String commentId) {
        UUID id = parse(commentId);
        if (id == null) {
            return Optional.empty();
        }
        return queries.findSnapshot(id).map(row -> new CommentSnapshot(
                row.id().toString(),
                row.version(),
                row.roomId().toString(),
                row.postId().toString(),
                JUDGED,
                row.userId().toString(),
                row.content(),
                rfc3339(row.createdAt())));
    }

    private static UUID parse(String value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // CaseSnapshotAssembler.rfc3339 와 같은 규칙. 그 메서드는 package-private 이다
    private static String rfc3339(OffsetDateTime time) {
        return time.withOffsetSameInstant(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
