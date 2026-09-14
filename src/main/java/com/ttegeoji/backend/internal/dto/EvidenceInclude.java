package com.ttegeoji.backend.internal.dto;

/** 10 §4.2 resolve-evidence include 값. 이름이 JSON 값 그대로다(AI ports/backend.py EvidenceInclude). */
public enum EvidenceInclude {
    rules, aggregates, recent_verdicts, style_comments
}
