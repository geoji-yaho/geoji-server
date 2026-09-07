package com.ttegeoji.backend.api;

import com.ttegeoji.backend.domain.CrownHistory;
import com.ttegeoji.backend.dto.CrownHistoryResponse;
import com.ttegeoji.backend.dto.CrownUserRequest;
import com.ttegeoji.backend.repository.CrownHistoryRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/rooms/{roomId}/crown")
@RequiredArgsConstructor
public class CrownController {

    private final CrownHistoryRepository crownHistoryRepository;

    @GetMapping
    public ResponseEntity<CrownHistoryResponse> current(@PathVariable UUID roomId) {
        return crownHistoryRepository.findByRoomIdAndEndedAtIsNull(roomId)
                .map(CrownHistoryResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/history")
    public ResponseEntity<List<CrownHistoryResponse>> history(@PathVariable UUID roomId) {
        List<CrownHistoryResponse> history = crownHistoryRepository.findByRoomIdOrderByStartedAtDesc(roomId)
                .stream().map(CrownHistoryResponse::from).toList();
        return ResponseEntity.ok(history);
    }

    // 즉위. 현재 왕이 있으면 먼저 폐위(endedAt 기록)시킨 뒤 새 재위를 연다 —
    // room당 endedAt이 null인 행은 최대 1개(부분 유니크 인덱스)이므로 순서를 지켜야 한다.
    @PostMapping
    @Transactional
    public ResponseEntity<CrownHistoryResponse> crown(
            @PathVariable UUID roomId,
            @Valid @RequestBody CrownUserRequest request) {

        crownHistoryRepository.findByRoomIdAndEndedAtIsNull(roomId).ifPresent(current -> {
            if (current.getUserId().equals(request.userId())) {
                throw new IllegalStateException("이미 재위 중인 왕입니다.");
            }
            current.setEndedAt(OffsetDateTime.now());
            current.setDethronedByExpenseId(request.dethronedByExpenseId());
            // saveAndFlush로 UPDATE를 먼저 내보내야 한다. save()만 쓰면 Hibernate가 같은 flush 안에서
            // INSERT를 UPDATE보다 먼저 실행해서, 새 왕을 넣는 순간 옛 왕의 endedAt이 아직 null이라
            // 부분 유니크 인덱스(room당 endedAt is null 최대 1개)를 건드려 즉시 실패한다.
            crownHistoryRepository.saveAndFlush(current);
        });

        CrownHistory reign = CrownHistory.builder()
                .roomId(roomId)
                .userId(request.userId())
                .build();

        // saveAndFlush: startedAt은 DB 기본값(@Generated)이라 실제 INSERT가 나가야 채워진다.
        reign = crownHistoryRepository.saveAndFlush(reign);
        return ResponseEntity.status(HttpStatus.CREATED).body(CrownHistoryResponse.from(reign));
    }
}
