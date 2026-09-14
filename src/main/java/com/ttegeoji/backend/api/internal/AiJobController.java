package com.ttegeoji.backend.api.internal;

import com.ttegeoji.backend.config.InternalApiException;
import com.ttegeoji.backend.internal.CaseSnapshotAssembler;
import com.ttegeoji.backend.internal.EvidenceResolver;
import com.ttegeoji.backend.internal.dto.CaseSnapshot;
import com.ttegeoji.backend.internal.dto.ResolveEvidenceRequest;
import com.ttegeoji.backend.internal.dto.ResolveEvidenceResponse;
import com.ttegeoji.backend.jobs.JobQueries;
import com.ttegeoji.backend.jobs.JobRow;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 워커가 부르는 job 단위 읽기 API(10 §4.1·§4.2). 인증은 ServiceTokenFilter 가 먼저 끝낸다(10 §4.7).
 * 거부 본문은 {"code"} 하나. 로그에 사유·댓글 원문·토큰을 남기지 않으려고 여기서는 아무것도 로그하지 않는다.
 */
@RestController
@RequestMapping("/internal/v1/ai-jobs/{job_id}")
@RequiredArgsConstructor
public class AiJobController {

    static final String GENERATION_HEADER = "X-Generation-Id";

    private final JobQueries jobQueries;
    private final CaseSnapshotAssembler caseSnapshotAssembler;
    private final EvidenceResolver evidenceResolver;

    @GetMapping("/snapshot")
    public CaseSnapshot snapshot(@PathVariable("job_id") String jobId,
                                 @RequestHeader(value = GENERATION_HEADER, required = false) String generationId) {
        return caseSnapshotAssembler.assemble(requireActiveJob(jobId, generationId));
    }

    // 순서는 가짜 백엔드와 같게 본문 422 → job 409
    @PostMapping("/resolve-evidence")
    public ResolveEvidenceResponse resolveEvidence(@PathVariable("job_id") String jobId,
                                                   @RequestHeader(value = GENERATION_HEADER, required = false) String generationId,
                                                   @RequestBody(required = false) byte[] body) {
        ResolveEvidenceRequest request = ResolveEvidenceRequest.parse(body);
        return evidenceResolver.resolve(requireActiveJob(jobId, generationId), request);
    }

    /** 10 §4.1 job 존재 ∧ RUNNING ∧ X-Generation-Id 일치 ∧ lease 유효. 헤더가 없거나 UUID 가 아니어도 같은 409 다. */
    private JobRow requireActiveJob(String jobId, String generationId) {
        UUID job = strictUuid(jobId);
        UUID generation = strictUuid(generationId);
        if (job == null || generation == null) {
            throw stale();
        }
        return jobQueries.findRunningWithValidLease(job, generation).orElseThrow(AiJobController::stale);
    }

    // UUID.fromString 은 "1-1-1-1-1" 같은 짧은 형태도 받아 다른 id 로 읽을 수 있어 표준 36자만 받는다
    private static UUID strictUuid(String value) {
        if (value == null || value.length() != 36) {
            return null;
        }
        try {
            UUID uuid = UUID.fromString(value);
            return uuid.toString().equalsIgnoreCase(value) ? uuid : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static InternalApiException stale() {
        return new InternalApiException(HttpStatus.CONFLICT, "STALE_GENERATION");
    }
}
