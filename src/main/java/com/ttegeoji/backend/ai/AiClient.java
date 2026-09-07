package com.ttegeoji.backend.ai;

/**
 * AI 서비스(상 발명, 도전 과제 서술, 순찰 문구 생성)와의 경계.
 * AI 쪽 API 문서가 아직 없어서 지금은 {@link StubAiClient}가 하드코딩된 문구를 돌려준다.
 * 문서가 나오면 이 인터페이스를 구현하는 실제 HTTP 클라이언트를 새로 만들고
 * StubAiClient의 @Component를 떼거나 지우면 된다 — 호출부(컨트롤러)는 안 바꿔도 된다.
 */
public interface AiClient {

    AwardCopy inventWeeklyAward(String statsSummary);

    String writeChallengeNarrative(String itemsSummary);

    String writePatrolRiskReason(String nickname, String riskWindowSummary);

    record AwardCopy(String title, String description) {
    }
}
