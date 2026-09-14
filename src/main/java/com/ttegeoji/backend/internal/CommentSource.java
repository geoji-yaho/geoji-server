package com.ttegeoji.backend.internal;

import com.ttegeoji.backend.internal.dto.CommentSnapshot;

import java.util.Optional;

/**
 * RETAIN comment.approved 원본 댓글 조회(10 §4.1). post_comments 는 W5 작업이라 지금은 구현이 늘 비어 있다.
 * 댓글이 없으면 snapshot 은 404 NOT_FOUND.
 */
public interface CommentSource {

    Optional<CommentSnapshot> find(String commentId);
}
