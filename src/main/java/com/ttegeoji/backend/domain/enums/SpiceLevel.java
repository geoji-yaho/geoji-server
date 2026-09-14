package com.ttegeoji.backend.domain.enums;

// Postgres enum 라벨과 이름이 정확히 일치해야 NAMED_ENUM 매핑이 변환 없이 동작한다.
// AI팀 backend-contract.md §15.2 D-21 확정: "프론트 값이 API 표준" — geoji-web이 쓰는
// 소문자 표기 그대로 맞춘다. 순한맛=mild, 매운맛=spicy, 지옥맛=hell.
public enum SpiceLevel {
    mild, spicy, hell
}
