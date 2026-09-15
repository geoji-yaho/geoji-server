package com.ttegeoji.backend.api;

import com.ttegeoji.backend.ai.AiClient;
import com.ttegeoji.backend.domain.Expense;
import com.ttegeoji.backend.domain.ExpenseTrial;
import com.ttegeoji.backend.domain.ExpenseVote;
import com.ttegeoji.backend.domain.Room;
import com.ttegeoji.backend.domain.enums.ExpenseSource;
import com.ttegeoji.backend.domain.enums.VerdictType;
import com.ttegeoji.backend.dto.CastVoteRequest;
import com.ttegeoji.backend.dto.TrialResponse;
import com.ttegeoji.backend.repository.ExpenseRepository;
import com.ttegeoji.backend.repository.ExpenseTrialRepository;
import com.ttegeoji.backend.repository.ExpenseVoteRepository;
import com.ttegeoji.backend.repository.RoomMemberRepository;
import com.ttegeoji.backend.repository.RoomRepository;
import com.ttegeoji.backend.security.CurrentUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

// 지출 재판 — 와이어프레임 흐름 B.
// "돈 썼어요"(quick_tap): 유죄/무죄 투표 → AI 판결 → 유죄면 형 집행.
// "살까 말까"(purchase_check): 동의/기각 투표 → 판결(형량 없음). S-06·S-14.
// 마감 시한은 별도로 두지 않고 방 설정(rooms.vote_deadline_minutes)을 그대로 쓴다.
@RestController
@RequestMapping("/api/rooms/{roomId}/expenses/{expenseId}")
@RequiredArgsConstructor
public class ExpenseTrialController {

    private final ExpenseTrialRepository trialRepository;
    private final ExpenseVoteRepository voteRepository;
    private final ExpenseRepository expenseRepository;
    private final RoomRepository roomRepository;
    private final RoomMemberRepository roomMemberRepository;
    private final AiClient aiClient;

    private static final int PURCHASE_QUORUM = 2;

    private static boolean verdictFits(ExpenseSource source, VerdictType verdict) {
        return source == ExpenseSource.purchase_check
                ? verdict == VerdictType.agree || verdict == VerdictType.disagree
                : verdict == VerdictType.guilty || verdict == VerdictType.notGuilty;
    }

    // 재판은 지출이 방 피드에 처음 노출되는 순간 암묵적으로 열린다 — 없으면 지금 만든다.
    private ExpenseTrial getOrCreateTrial(UUID roomId, UUID expenseId) {
        return trialRepository.findByRoomIdAndExpenseId(roomId, expenseId)
                .orElseGet(() -> {
                    Expense expense = expenseRepository.findById(expenseId)
                            .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 지출 기록입니다."));
                    if (!roomMemberRepository.existsById_RoomIdAndId_UserId(roomId, expense.getUserId())) {
                        throw new IllegalArgumentException("이 지출은 해당 방에서 보이지 않습니다.");
                    }
                    Room room = roomRepository.findById(roomId)
                            .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 방입니다."));

                    ExpenseTrial trial = ExpenseTrial.builder()
                            .roomId(roomId)
                            .expenseId(expenseId)
                            .votingDeadline(OffsetDateTime.now().plusMinutes(room.getVoteDeadlineMinutes()))
                            .build();
                    return trialRepository.saveAndFlush(trial);
                });
    }

    @GetMapping("/trial")
    public ResponseEntity<TrialResponse> getTrial(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID roomId, @PathVariable UUID expenseId) {

        UUID currentUserId = CurrentUser.idOf(jwt);
        if (!roomMemberRepository.existsById_RoomIdAndId_UserId(roomId, currentUserId)) {
            throw new IllegalStateException("이 방의 멤버만 볼 수 있습니다.");
        }

        ExpenseTrial trial = getOrCreateTrial(roomId, expenseId);
        List<ExpenseVote> votes = voteRepository.findByTrialIdOrderByCreatedAtAsc(trial.getId());
        return ResponseEntity.ok(TrialResponse.from(trial, votes, currentUserId));
    }

    @PostMapping("/votes")
    public ResponseEntity<TrialResponse> vote(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID roomId,
            @PathVariable UUID expenseId,
            @Valid @RequestBody CastVoteRequest request) {

        UUID voterId = CurrentUser.idOf(jwt);
        if (!roomMemberRepository.existsById_RoomIdAndId_UserId(roomId, voterId)) {
            throw new IllegalStateException("이 방의 멤버만 투표할 수 있습니다.");
        }
        Expense expense = expenseRepository.findById(expenseId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 지출 기록입니다."));
        // 돈 썼어요는 guilty/notGuilty, 살까 말까는 agree/disagree 만. 섞이면 판결 집계가 깨진다(dismissed 는 투표값이 아니다)
        if (!verdictFits(expense.getSource(), request.verdict())) {
            throw new IllegalArgumentException(expense.getSource() == ExpenseSource.purchase_check
                    ? "살까 말까는 agree 또는 disagree만 투표할 수 있습니다."
                    : "지출 재판은 guilty 또는 notGuilty만 투표할 수 있습니다.");
        }
        if (expense.getUserId().equals(voterId)) {
            throw new IllegalStateException("본인 지출에는 투표할 수 없습니다.");
        }

        ExpenseTrial trial = getOrCreateTrial(roomId, expenseId);
        if (trial.getVerdict() != null) {
            throw new IllegalStateException("이미 판결이 확정된 재판입니다.");
        }
        if (trial.getVotingDeadline().isBefore(OffsetDateTime.now())) {
            throw new IllegalStateException("투표가 마감되었습니다.");
        }
        if (voteRepository.existsByTrialIdAndVoterUserId(trial.getId(), voterId)) {
            throw new IllegalStateException("이미 투표했습니다.");
        }

        ExpenseVote vote = ExpenseVote.builder()
                .trialId(trial.getId())
                .voterUserId(voterId)
                .verdict(request.verdict())
                .reason(request.reason())
                .build();
        voteRepository.saveAndFlush(vote);

        List<ExpenseVote> votes = voteRepository.findByTrialIdOrderByCreatedAtAsc(trial.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(TrialResponse.from(trial, votes, voterId));
    }

    // MVP 데모용 "즉시 판결" 버튼 — WeeklyAwardController의 /generate와 같은 패턴.
    // 마감을 기다리지 않고 지금까지 모인 표로 바로 판결한다. 동률이면 무죄로 처리한다.
    @PostMapping("/trial/judge")
    public ResponseEntity<TrialResponse> judge(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID roomId, @PathVariable UUID expenseId) {

        UUID currentUserId = CurrentUser.idOf(jwt);
        if (!roomMemberRepository.existsById_RoomIdAndId_UserId(roomId, currentUserId)) {
            throw new IllegalStateException("이 방의 멤버만 판결을 확정할 수 있습니다.");
        }

        ExpenseTrial trial = trialRepository.findByRoomIdAndExpenseId(roomId, expenseId)
                .orElseThrow(() -> new IllegalArgumentException("아직 투표가 시작되지 않았습니다."));
        if (trial.getVerdict() != null) {
            throw new IllegalStateException("이미 판결이 확정된 재판입니다.");
        }

        List<ExpenseVote> votes = voteRepository.findByTrialIdOrderByCreatedAtAsc(trial.getId());
        Expense expense = expenseRepository.findById(expenseId).orElseThrow();
        Room room = roomRepository.findById(roomId).orElseThrow();
        int ruleCount = room.getRules() == null ? 0 : room.getRules().length;
        String caseSummary = "%d원 지출, 사유: %s, 참고된 방 규칙 %d개".formatted(
                expense.getAmount(), expense.getMemo() == null ? "미기재" : expense.getMemo(), ruleCount);

        // 살까 말까: 정족수 2표 미달이면 dismissed, 동의가 기각보다 많으면 agree, 동률 포함 그 밖은 disagree. 형량 없음
        if (expense.getSource() == ExpenseSource.purchase_check) {
            long agreeVotes = votes.stream().filter(v -> v.getVerdict() == VerdictType.agree).count();
            long disagreeVotes = votes.stream().filter(v -> v.getVerdict() == VerdictType.disagree).count();
            VerdictType verdict = agreeVotes + disagreeVotes < PURCHASE_QUORUM ? VerdictType.dismissed
                    : agreeVotes > disagreeVotes ? VerdictType.agree : VerdictType.disagree;

            trial.setVerdict(verdict);
            trial.setVerdictText(aiClient.judgePurchase(caseSummary, agreeVotes, disagreeVotes, verdict));
            trial.setJudgedAt(OffsetDateTime.now());
            trialRepository.saveAndFlush(trial);
            return ResponseEntity.ok(TrialResponse.from(trial, votes, currentUserId));
        }

        if (votes.isEmpty()) {
            throw new IllegalStateException("아직 투표가 없어 판결할 수 없습니다.");
        }

        long guiltyVotes = votes.stream().filter(v -> v.getVerdict() == VerdictType.guilty).count();
        long notGuiltyVotes = votes.stream().filter(v -> v.getVerdict() == VerdictType.notGuilty).count();
        boolean isGuilty = guiltyVotes > notGuiltyVotes;

        AiClient.VerdictCopy copy = aiClient.judge(caseSummary, guiltyVotes, notGuiltyVotes, isGuilty);

        trial.setVerdict(isGuilty ? VerdictType.guilty : VerdictType.notGuilty);
        trial.setVerdictText(copy.verdictText());
        trial.setJudgedAt(OffsetDateTime.now());
        if (isGuilty) {
            trial.setSentenceDays(copy.sentenceDays());
            trial.setSentenceStartedAt(trial.getJudgedAt());
            trial.setSentenceEndedAt(trial.getJudgedAt().plusDays(copy.sentenceDays()));
        }
        trialRepository.saveAndFlush(trial);

        return ResponseEntity.ok(TrialResponse.from(trial, votes, currentUserId));
    }
}
