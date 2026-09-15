package com.ttegeoji.backend.dto;

import com.ttegeoji.backend.domain.ExpenseTrial;
import com.ttegeoji.backend.domain.ExpenseVote;
import com.ttegeoji.backend.domain.enums.VerdictType;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

// guilty/notGuilty 는 돈 썼어요, agree/disagree 는 살까 말까 재판의 표 수. 해당 없는 쪽은 늘 0 이다
public record TrialResponse(
        UUID id, UUID roomId, UUID expenseId, OffsetDateTime votingDeadline,
        VerdictType verdict, String verdictText, Integer sentenceDays,
        OffsetDateTime sentenceStartedAt, OffsetDateTime sentenceEndedAt, OffsetDateTime judgedAt,
        long guiltyVotes, long notGuiltyVotes, long agreeVotes, long disagreeVotes,
        VerdictType myVote, List<VoteResponse> votes
) {
    public static TrialResponse from(ExpenseTrial trial, List<ExpenseVote> votes, UUID currentUserId) {
        VerdictType myVote = votes.stream()
                .filter(v -> v.getVoterUserId().equals(currentUserId))
                .map(ExpenseVote::getVerdict)
                .findFirst().orElse(null);

        return new TrialResponse(
                trial.getId(), trial.getRoomId(), trial.getExpenseId(), trial.getVotingDeadline(),
                trial.getVerdict(), trial.getVerdictText(), trial.getSentenceDays(),
                trial.getSentenceStartedAt(), trial.getSentenceEndedAt(), trial.getJudgedAt(),
                count(votes, VerdictType.guilty), count(votes, VerdictType.notGuilty),
                count(votes, VerdictType.agree), count(votes, VerdictType.disagree),
                myVote, votes.stream().map(VoteResponse::from).toList());
    }

    private static long count(List<ExpenseVote> votes, VerdictType verdict) {
        return votes.stream().filter(v -> v.getVerdict() == verdict).count();
    }
}
