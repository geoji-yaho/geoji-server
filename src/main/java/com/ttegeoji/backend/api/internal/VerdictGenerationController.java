package com.ttegeoji.backend.api.internal;

import com.ttegeoji.backend.verdict.BeginGenerationService;
import com.ttegeoji.backend.verdict.GenerationFailedService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

// 10 §4.3·§4.6 워커 → 백엔드. 인증은 ServiceTokenFilter, 거부 본문 {"code"} 는 ApiExceptionHandler(§4.7)
@RestController
@RequestMapping("/internal/v1/verdicts/{verdictId}")
@RequiredArgsConstructor
public class VerdictGenerationController {

    private final BeginGenerationService beginGenerationService;
    private final GenerationFailedService generationFailedService;

    @PostMapping("/begin-generation")
    public BeginGenerationService.Response begin(@PathVariable UUID verdictId,
                                                 @Valid @RequestBody BeginGenerationService.Request request) {
        return beginGenerationService.begin(verdictId, request);
    }

    @PostMapping("/generation-failed")
    public GenerationFailedService.Response failed(@PathVariable UUID verdictId,
                                                   @Valid @RequestBody GenerationFailedService.Request request,
                                                   @RequestHeader(name = "X-Trace-Id", required = false) String traceId) {
        return generationFailedService.fail(verdictId, request, traceId);
    }
}
