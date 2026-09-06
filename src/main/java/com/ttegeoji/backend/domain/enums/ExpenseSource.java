package com.ttegeoji.backend.domain.enums;

// 소문자 상수: Postgres enum 라벨과 이름이 정확히 일치해야 NAMED_ENUM 매핑이 변환 없이 동작한다.
public enum ExpenseSource {
    quick_tap, purchase_check
}
