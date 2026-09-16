package com.ttegeoji.backend.verdictview;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 거지력(debt_score) 계산. 주간 배치가 채우기로 했던 값(10 §9)이지만 배치가 없어 전원 null 이었다.
 * 방 인원이 작아 조회 때 계산한다. 공식은 프론트 {@code shared/domain/score.ts} 와 같다.
 *
 * <p>점수 = 예산 점수(0~50) + 평결 점수(0~10). 무지출·참여 점수는 프론트도 0 이라 빼 뒀다.
 * 달 기준은 KST 다.
 */
@Component
@RequiredArgsConstructor
public class DebtScoreQueries {

    static final int BUDGET_SCORE_MAX = 50;
    static final int VERDICT_SCORE_MAX = 10;
    static final int VERDICT_SCORE_NEUTRAL = 5;
    /** 달 초에 baseline 이 0 에 가까워 점수가 튀는 것을 막는다(프론트와 같은 값) */
    static final int MIN_ELAPSED_DAYS = 3;

    private final JdbcTemplate jdbc;

    private record Row(UUID userId, Integer monthlyBudget, long spentThisMonth, int judged, int acquitted,
                       int elapsedDays, int daysInMonth) {
    }

    /**
     * 방 멤버별 거지력. 점수를 낼 수 없는 멤버(예산 미설정)는 값이 없어 맨 뒤로 간다.
     *
     * <p>이번 달 지출은 본인이 쓴 {@code spent} 게시물 합이고, 평결 점수는 확정된 유죄·무죄 중
     * 무죄 비율이다. 살까 말까(considering)는 지출이 아니라 양쪽 다 세지 않는다.
     */
    public Map<UUID, BigDecimal> forRoom(UUID roomId) {
        Map<UUID, BigDecimal> scores = new HashMap<>();
        jdbc.query("""
                WITH member AS (
                    SELECT rm.user_id, p.monthly_budget
                      FROM room_members rm
                      JOIN profiles p ON p.id = rm.user_id
                     WHERE rm.room_id = ?
                ), month AS (
                    SELECT date_trunc('month', timezone('Asia/Seoul', now())) AS start_kst,
                           EXTRACT(DAY FROM timezone('Asia/Seoul', now()))::int AS day_of_month,
                           EXTRACT(DAY FROM (date_trunc('month', timezone('Asia/Seoul', now()))
                                             + interval '1 month - 1 day'))::int AS days_in_month
                )
                SELECT m.user_id, m.monthly_budget, month.day_of_month, month.days_in_month,
                       COALESCE((SELECT sum(po.amount_krw) FROM posts po
                                  WHERE po.author_id = m.user_id AND po.deleted_at IS NULL
                                    AND po.post_type = 'spent'
                                    AND timezone('Asia/Seoul', po.created_at) >= month.start_kst), 0) AS spent,
                       (SELECT count(*) FROM posts po JOIN verdicts v ON v.post_id = po.id
                         WHERE po.author_id = m.user_id AND po.deleted_at IS NULL
                           AND v.jury_result IN ('guilty', 'notGuilty')) AS judged,
                       (SELECT count(*) FROM posts po JOIN verdicts v ON v.post_id = po.id
                         WHERE po.author_id = m.user_id AND po.deleted_at IS NULL
                           AND v.jury_result = 'notGuilty') AS acquitted
                  FROM member m CROSS JOIN month
                """, rs -> {
            Row row = new Row(
                    rs.getObject("user_id", UUID.class),
                    rs.getObject("monthly_budget", Integer.class),
                    rs.getLong("spent"),
                    rs.getInt("judged"),
                    rs.getInt("acquitted"),
                    rs.getInt("day_of_month"),
                    rs.getInt("days_in_month"));
            BigDecimal score = score(row);
            if (score != null) {
                scores.put(row.userId(), score);
            }
        }, roomId);
        return scores;
    }

    /**
     * 예산이 0 이면(온보딩에서 안 정한 상태다. 컬럼이 NOT NULL 이라 0 으로 들어온다)
     * 기준선이 없어 점수를 낼 수 없다. 0 점으로 깎지 않고 "집계 전"(null)로 두어 맨 뒤로 보낸다.
     */
    private static BigDecimal score(Row row) {
        if (row.monthlyBudget() == null || row.monthlyBudget() <= 0) {
            return null;
        }
        long total = Math.round(budgetScore(row) + verdictScore(row));
        return BigDecimal.valueOf(total);
    }

    private static double budgetScore(Row row) {
        int elapsed = Math.max(row.elapsedDays(), MIN_ELAPSED_DAYS);
        double baseline = (double) row.monthlyBudget() * elapsed / row.daysInMonth();
        if (baseline <= 0) {
            return 0;
        }
        double raw = BUDGET_SCORE_MAX * (1 - row.spentThisMonth() / baseline);
        return Math.min(BUDGET_SCORE_MAX, Math.max(0, raw));
    }

    private static double verdictScore(Row row) {
        if (row.judged() == 0) {
            return VERDICT_SCORE_NEUTRAL;
        }
        return (double) VERDICT_SCORE_MAX * row.acquitted() / row.judged();
    }
}
