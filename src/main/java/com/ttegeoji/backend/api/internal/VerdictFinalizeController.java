package com.ttegeoji.backend.api.internal;

import com.ttegeoji.backend.verdict.FinalizeService;
import com.ttegeoji.backend.verdict.FinalizeService.FinalizeResult;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 10 §5 finalize. 워커가 서비스 토큰으로 부른다(10 §4.7). request_hash 가 본문 바이트 sha256 이라
 * DTO 로 바인딩하지 않고 원문 바이트를 그대로 서비스에 넘긴다. 빈 본문도 서비스가 422 INVALID_DRAFT 로 거부한다.
 */
@RestController
@RequiredArgsConstructor
public class VerdictFinalizeController {

    private final FinalizeService finalizeService;

    @PostMapping("/internal/v1/verdicts/{id}/finalize")
    public Map<String, Object> finalizeVerdict(@PathVariable("id") String id,
                                               @RequestBody(required = false) byte[] body) {
        FinalizeResult result = finalizeService.finalizeVerdict(id, body);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("verdict_id", result.verdictId().toString());
        response.put("text_version", result.textVersion());
        response.put("committed_at", result.committedAt().toString());
        return response;
    }
}
