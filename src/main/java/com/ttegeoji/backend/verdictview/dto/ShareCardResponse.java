package com.ttegeoji.backend.verdictview.dto;

import java.util.List;

/**
 * 10 §9 share-card. 공개 허용 문구와 이미지 metadata 만 담는다.
 * 금액·item·사유·투표 사유·근거 원문·배심원·양형 이유·source 는 필드 자체가 없다.
 */
public record ShareCardResponse(
        String postId,
        String postType,
        String juryStatus,
        String intensity,
        String headline,
        List<String> statement,
        String sentence,
        String sentenceLabel,
        MemeView meme
) {
}
