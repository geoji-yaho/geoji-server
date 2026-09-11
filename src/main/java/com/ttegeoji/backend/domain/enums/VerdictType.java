package com.ttegeoji.backend.domain.enums;

// Postgres enum 라벨과 이름이 정확히 일치해야 NAMED_ENUM 매핑이 변환 없이 동작한다.
// AI팀 backend-contract.md의 평결 enum(GUILTY/NOT_GUILTY/APPROVED/REJECTED)에 이름을 맞췄다.
// APPROVED/REJECTED("살까 말까" 구매 동의/기각)는 이번 지출 재판 기능의 대상이 아니라서
// 지금은 어떤 코드도 이 두 값을 만들지 않는다 — 나중에 그 흐름을 만들 때 쓸 자리만 미리 맞춰둔 것.
public enum VerdictType {
    GUILTY, NOT_GUILTY, APPROVED, REJECTED
}
