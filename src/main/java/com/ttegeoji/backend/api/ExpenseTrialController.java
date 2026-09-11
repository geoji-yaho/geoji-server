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

// 지출 재판(유죄/무죄 투표 → AI 판결 → 형 집행) — 와이어프레임 흐름 B.
// "돈 썼어요"(quick_tap) 지출만 대상이다. "살까 말까"(purchase_check)는 재판 대상이 아니다.
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

    // 재판은 지출이 방 피드에 처음 노출되는 순간 암묵적으로 열린다 — 없으면 지금 만든다.
    private ExpenseTrial getOrCreateTrial(UUID roomId, UUID expenseId) {
        return trialRepository.findByRoomIdAndExpenseId(roomId, expenseId)
                .orElseGet(() -> {
                    Expense expense = expenseRepository.findById(expenseId)
                            .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 지출 기록입니다."));
                    if (expense.getSource() != ExpenseSource.quick_tap) {
                        throw new IllegalArgumentException("\"살까 말까\" 기록은 재판 대상이 아닙니다.");
                    }
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
        // VerdictType엔 APPROVED/REJECTED("살까 말까" 구매 동의/기각)도 있지만, 지출 재판은
        // GUILTY/NOT_GUILTY만 유효하다 — 여기서 막지 않으면 판결 집계(guilty vs 나머지)가 깨진다.
        if (request.verdict() != VerdictType.GUILTY && request.verdict() != VerdictType.NOT_GUILTY) {
            throw new IllegalArgumentException("지출 재판은 GUILTY 또는 NOT_GUILTY만 투표할 수 있습니다.");
        }

        Expense expense = expenseRepository.findById(expenseId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 지출 기록입니다."));
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
        if (votes.isEmpty()) {
            throw new IllegalStateException("아직 투표가 없어 판결할 수 없습니다.");
        }

        long guiltyVotes = votes.stream().filter(v -> v.getVerdict() == VerdictType.GUILTY).count();
        long notGuiltyVotes = votes.size() - guiltyVotes;
        boolean isGuilty = guiltyVotes > notGuiltyVotes;

        Expense expense = expenseRepository.findById(expenseId).orElseThrow();
        Room room = roomRepository.findById(roomId).orElseThrow();
        int ruleCount = room.getRules() == null ? 0 : room.getRules().length;
        String caseSummary = "%d원 지출, 사유: %s, 참고된 방 규칙 %d개".formatted(
                expense.getAmount(), expense.getMemo() == null ? "미기재" : expense.getMemo(), ruleCount);

        AiClient.VerdictCopy copy = aiClient.judge(caseSummary, guiltyVotes, notGuiltyVotes, isGuilty);

        trial.setVerdict(isGuilty ? VerdictType.GUILTY : VerdictType.NOT_GUILTY);
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
