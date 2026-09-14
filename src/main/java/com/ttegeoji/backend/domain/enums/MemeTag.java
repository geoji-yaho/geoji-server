package com.ttegeoji.backend.domain.enums;

// 짤 태그 5종(10 §2·§11, contracts MemeTag). 기획서 대문자 유지. DB 는 text + CHECK.
public enum MemeTag {
    GUILTY_HEAVY, GUILTY_LIGHT, NOT_GUILTY, APPROVED, REJECTED
}
