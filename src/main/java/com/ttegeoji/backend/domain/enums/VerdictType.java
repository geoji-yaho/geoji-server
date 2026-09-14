package com.ttegeoji.backend.domain.enums;

// Postgres enum 라벨과 이름이 정확히 일치해야 NAMED_ENUM 매핑이 변환 없이 동작한다.
// AI팀 backend-contract.md §15.2 D-21 확정: "프론트 값이 API 표준" — geoji-web의
// camelCase 표기 그대로 맞춘다(대문자 SCREAMING_SNAKE 아님).
// agree/disagree("살까 말까" 구매 동의/기각)·dismissed(정족수 미달 각하)는 이번
// 지출 재판 기능의 대상이 아니라서 지금은 어떤 코드도 이 값들을 만들지 않는다 —
// 나중에 그 흐름을 만들 때 쓸 자리만 미리 맞춰둔 것.
public enum VerdictType {
    guilty, notGuilty, agree, disagree, dismissed
}
