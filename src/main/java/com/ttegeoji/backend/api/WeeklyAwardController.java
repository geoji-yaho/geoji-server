package com.ttegeoji.backend.api;

import com.ttegeoji.backend.domain.WeeklyAward;
import com.ttegeoji.backend.dto.CreateWeeklyAwardRequest;
import com.ttegeoji.backend.dto.WeeklyAwardResponse;
import com.ttegeoji.backend.repository.WeeklyAwardRepository;
import com.ttegeoji.backend.util.Json;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

// 상 발명(패턴 탐지 + LLM 작명)은 이 컨트롤러의 책임이 아니다. 이미 만들어진 결과를 저장/조회만 한다.
@RestController
@RequestMapping("/api/rooms/{roomId}/awards")
@RequiredArgsConstructor
public class WeeklyAwardController {

    private final WeeklyAwardRepository weeklyAwardRepository;

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

        award = weeklyAwardRepository.save(award);
        return ResponseEntity.status(HttpStatus.CREATED).body(WeeklyAwardResponse.from(award));
    }
}
