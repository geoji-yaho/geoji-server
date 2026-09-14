package com.ttegeoji.backend.verdictview.dto;

import java.util.List;

/** verdict-view-v1 VerdictTextView. sentence·sentenceLabel 은 형량이 없는 결과(무죄·동의·기각)면 null */
public record VerdictTextView(
        String intensity,
        String headline,
        List<String> statement,
        String sentence,
        String sentenceLabel,
        String sentencingReason,
        String source,
        MemeView meme
) {
}
