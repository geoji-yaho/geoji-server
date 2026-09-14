package com.ttegeoji.backend.domain.enums;

// verdicts.text_status(10 §2). 재시도 중에도 TEMPLATE_READY 를 유지한다(RETRYING 없음). DB 는 text + CHECK.
public enum TextStatus {
    PENDING, GENERATING, TEMPLATE_READY, AI_READY
}
