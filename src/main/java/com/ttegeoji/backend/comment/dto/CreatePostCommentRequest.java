package com.ttegeoji.backend.comment.dto;

/**
 * SPEC 게시물 댓글 작성. roomId 를 UUID 가 아니라 문자열로 받는 것은 UUID 가 아닐 때도 400 {"message"} 로 답하려는 것이다
 * (UUID 로 받으면 역직렬화 실패가 Spring 기본 형식이 된다).
 */
public record CreatePostCommentRequest(String roomId, String content) {
}
