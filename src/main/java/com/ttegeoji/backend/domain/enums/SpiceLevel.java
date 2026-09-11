package com.ttegeoji.backend.domain.enums;

// Postgres enum 라벨과 이름이 정확히 일치해야 NAMED_ENUM 매핑이 변환 없이 동작한다.
// AI팀 backend-contract.md D-21 결정(MILD/SPICY/HELL)에 맞춘 대문자 표기.
// 순한맛=MILD, 매운맛=SPICY, 지옥맛=HELL.
public enum SpiceLevel {
    MILD, SPICY, HELL
}
