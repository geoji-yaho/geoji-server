package com.ttegeoji.backend.domain.enums;

// sentence_source(AI/RULE)·reason_source·verdict_texts.source(AI/TEMPLATE) 공용(10 §2). 컬럼별 허용 값은 DB CHECK 가 막는다.
public enum ContentSource {
    AI, TEMPLATE, RULE
}
