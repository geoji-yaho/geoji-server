package com.ttegeoji.backend.internal;

import com.ttegeoji.backend.internal.dto.ResolveEvidenceRequest;
import com.ttegeoji.backend.internal.dto.ResolveEvidenceResponse;
import com.ttegeoji.backend.jobs.JobRow;
import org.springframework.stereotype.Component;

/**
 * 10 §4.2 resolve-evidence 조립 자리. 뼈대다 — aggregates(burn_rate·tier·no_spend_days·rule_version)·sources 필터·
 * recent_verdicts 선정 규칙이 미결이라 코디네이터 답을 받은 뒤 채운다. 요청 본문 검증은 ResolveEvidenceRequest.parse 가 이미 했다.
 */
@Component
public class EvidenceResolver {

    public ResolveEvidenceResponse resolve(JobRow job, ResolveEvidenceRequest request) {
        throw new UnsupportedOperationException("resolve-evidence 는 아직 구현되지 않았다");
    }
}
