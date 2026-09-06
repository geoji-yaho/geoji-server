package com.ttegeoji.backend.api;

import com.ttegeoji.backend.domain.DailyLog;
import com.ttegeoji.backend.dto.CreateDailyLogRequest;
import com.ttegeoji.backend.dto.DailyLogResponse;
import com.ttegeoji.backend.repository.DailyLogRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.UUID;

// 하루로그 총평(서사 생성) 자체는 이 컨트롤러의 책임이 아니다. 결과를 저장/조회만 한다.
@RestController
@RequestMapping("/api/rooms/{roomId}/daily-logs")
@RequiredArgsConstructor
public class DailyLogController {

    private final DailyLogRepository dailyLogRepository;

    @GetMapping
    public ResponseEntity<DailyLogResponse> get(
            @PathVariable UUID roomId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {

        return dailyLogRepository.findByRoomIdAndLogDate(roomId, date)
                .map(DailyLogResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<DailyLogResponse> create(
            @PathVariable UUID roomId,
            @Valid @RequestBody CreateDailyLogRequest request) {

        DailyLog log = DailyLog.builder()
                .roomId(roomId)
                .logDate(request.logDate())
                .summary(request.summary())
                .collapseTime(request.collapseTime())
                .mvpUserId(request.mvpUserId())
                .build();

        log = dailyLogRepository.save(log);
        return ResponseEntity.status(HttpStatus.CREATED).body(DailyLogResponse.from(log));
    }
}
