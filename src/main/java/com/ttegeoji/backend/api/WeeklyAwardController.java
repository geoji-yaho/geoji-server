package com.ttegeoji.backend.api;

import com.ttegeoji.backend.ai.AiClient;
import com.ttegeoji.backend.domain.Expense;
import com.ttegeoji.backend.domain.WeeklyAward;
import com.ttegeoji.backend.domain.enums.AwardType;
import com.ttegeoji.backend.dto.CreateWeeklyAwardRequest;
import com.ttegeoji.backend.dto.WeeklyAwardResponse;
import com.ttegeoji.backend.repository.ExpenseRepository;
import com.ttegeoji.backend.repository.WeeklyAwardRepository;
import com.ttegeoji.backend.util.Json;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

// 상 발명(패턴 탐지 + LLM 작명)은 이 컨트롤러의 책임이 아니다. 이미 만들어진 결과를 저장/조회만 한다.
@RestController
@RequestMapping("/api/rooms/{roomId}/awards")
@RequiredArgsConstructor
public class WeeklyAwardController {

    private final WeeklyAwardRepository weeklyAwardRepository;
    private final ExpenseRepository expenseRepository;
    private final AiClient aiClient;

    @GetMapping
    public ResponseEntity<List<WeeklyAwardResponse>> list(
            @PathVariable UUID roomId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekStart) {

        List<WeeklyAwardResponse> awards = weeklyAwardRepository.findByRoomIdAndWeekStart(roomId, weekStart)
                .stream().map(WeeklyAwardResponse::from).toList();
        return ResponseEntity.ok(awards);
    }

    @PostMapping
    public ResponseEntity<WeeklyAwardResponse> create(
            @PathVariable UUID roomId,
            @Valid @RequestBody CreateWeeklyAwardRequest request) {

        WeeklyAward award = WeeklyAward.builder()
                .roomId(roomId)
                .weekStart(request.weekStart())
                .weekEnd(request.weekEnd())
                .awardType(request.awardType())
                .title(request.title())
                .winnerUserId(request.winnerUserId())
                .description(request.description())
                .statsSnapshot(Json.write(request.statsSnapshot()))
                .build();

        // saveAndFlush: createdAt은 DB 기본값(@Generated)이라 실제 INSERT가 나가야 채워진다.
        award = weeklyAwardRepository.saveAndFlush(award);
        return ResponseEntity.status(HttpStatus.CREATED).body(WeeklyAwardResponse.from(award));
    }

    // MVP 설계서 13장 "시상식 즉시 생성 버튼" — 데모에서 일주일을 기다릴 수 없어서 필요한 엔드포인트.
    // 통계는 최소한만(주간 최고 지출자) 직접 계산하고, 이름·문구는 AiClient(현재는 StubAiClient)에 맡긴다.
    // 진짜 패턴 탐지(요일 편중, 시간대 편중 등, 설계서 3-3)는 AI 쪽 API가 나온 뒤 여기에 얹으면 된다.
    @PostMapping("/generate")
    public ResponseEntity<WeeklyAwardResponse> generate(
            @PathVariable UUID roomId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekStart,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekEnd) {

        OffsetDateTime from = weekStart.atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
        OffsetDateTime to = weekEnd.plusDays(1).atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();

        List<Expense> weekExpenses = expenseRepository.findForRoomGrid(roomId, from, to);

        if (weekExpenses.isEmpty()) {
            throw new IllegalStateException("이번 주 지출 데이터가 없어 상을 만들 수 없습니다.");
        }

        Map<UUID, Integer> totalsByUser = weekExpenses.stream()
                .collect(Collectors.groupingBy(Expense::getUserId, Collectors.summingInt(Expense::getAmount)));

        UUID biggestSpender = totalsByUser.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElseThrow();
        int biggestTotal = totalsByUser.get(biggestSpender);

        String statsSummary = "이번 주 최고 지출자 userId=%s, 합계=%d원 (전체 지출 %d건)"
                .formatted(biggestSpender, biggestTotal, weekExpenses.size());

        AiClient.AwardCopy copy = aiClient.inventWeeklyAward(statsSummary);

        WeeklyAward award = WeeklyAward.builder()
                .roomId(roomId)
                .weekStart(weekStart)
                .weekEnd(weekEnd)
                .awardType(AwardType.invented)
                .title(copy.title())
                .winnerUserId(biggestSpender)
                .description(copy.description())
                .statsSnapshot(Json.write(Map.of(
                        "totalsByUser", totalsByUser,
                        "expenseCount", weekExpenses.size())))
                .build();

        award = weeklyAwardRepository.saveAndFlush(award);
        return ResponseEntity.status(HttpStatus.CREATED).body(WeeklyAwardResponse.from(award));
    }
}
