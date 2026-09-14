package com.ttegeoji.backend.internal;

import com.ttegeoji.backend.internal.dto.CommentSnapshot;
import org.springframework.stereotype.Component;

import java.util.Optional;

// post_comments(W5) 가 생기기 전의 자리. W5 가 실제 구현을 만들면 이 클래스를 지운다
@Component
class NoCommentSource implements CommentSource {

    @Override
    public Optional<CommentSnapshot> find(String commentId) {
        return Optional.empty();
    }
}
