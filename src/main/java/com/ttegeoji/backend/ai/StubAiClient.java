package com.ttegeoji.backend.ai;

import org.springframework.stereotype.Component;

/**
 * TODO(AI 연동): AI 팀 API 문서가 나오면 이 구현을 실제 호출로 교체할 것.
 * 지금은 데모/통합 테스트가 끝까지 돌아가도록 고정 문구만 반환한다 — 실제로 패턴을
 * 분석하거나 문장을 생성하지 않는다. 응답에 입력값을 그대로 끼워 넣어서, 나중에
 * "진짜 AI가 이 데이터를 받고 있었구나"를 눈으로 확인할 수 있게만 해뒀다.
 */
@Component
public class StubAiClient implements AiClient {

    @Override
    public AwardCopy inventWeeklyAward(String statsSummary) {
        return new AwardCopy(
                "이번 주의 수상한 지출상 (임시)",
                "AI 연동 전 데모용 문구입니다. 실제 서비스에서는 여기에 패턴을 분석한 상 이름과 "
                        + "수상평이 들어갑니다. 입력 통계: " + statsSummary
        );
    }

    @Override
    public String writeChallengeNarrative(String itemsSummary) {
        return "AI 연동 전 데모용 문구입니다. 실제로는 절감 항목(" + itemsSummary
                + ")을 근거로 실행 가능한 도전 문구가 생성됩니다.";
    }

    @Override
    public String writePatrolRiskReason(String nickname, String riskWindowSummary) {
        return nickname + "님, " + riskWindowSummary + " 시간대가 위험합니다. (AI 연동 전 데모용 문구)";
    }
}
