package com.ttegeoji.backend.dto;

import com.ttegeoji.backend.domain.ExpenseTrial;
import com.ttegeoji.backend.domain.ExpenseVote;
import com.ttegeoji.backend.domain.enums.VerdictType;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record TrialResponse(
        UUID id, UUID roomId, UUID expenseId, OffsetDateTime votingDeadline,
        VerdictType verdict, String verdictText, Integer sentenceDays,
        OffsetDateTime sentenceStartedAt, OffsetDateTime sentenceEndedAt, OffsetDateTime judgedAt,
        long guiltyVotes, long notGuiltyVotes, VerdictType myVote, List<VoteResponse> votes
) {
    public static TrialResponse from(ExpenseTrial trial, List<ExpenseVote> votes, UUID currentUserId) {
        long guilty = votes.stream().filter(v -> v.getVerdict() == VerdictType.guilty).count();
        long notGuilty = votes.size() - guilty;
        VerdictType myVote = votes.stream()
                .filter(v -> v.getVoterUserId().equals(currentUserId))
                .map(ExpenseVote::getVerdict)
                .findFirst().orElse(null);

        return new TrialResponse(
                trial.getId(), trial.getRoomId(), trial.getExpenseId(), trial.getVotingDeadline(),
                trial.getVerdict(), trial.getVerdictText(), trial.getSentenceDays(),
                trial.getSentenceStartedAt(), trial.getSentenceEndedAt(), trial.getJudgedAt(),
                guilty, notGuilty, myVote, votes.stream().map(VoteResponse::from).toList());
    }
}
