package com.ttegeoji.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * geoji.* 설정. 값은 환경변수에서 온다(application.yml). 비어 있어도 기동은 하되,
 * 서비스 토큰이 비면 내부 API 는 전부 401 이다(10 §4.7).
 *
 * @param internal               워커 → 백엔드 내부 API 설정
 * @param ai                     백엔드 → AI API 설정
 * @param guardrailPolicyVersion finalize 가 비교하는 정책 버전(10 §5, §16.1). GUARDRAIL_POLICY_VERSION
 */
@ConfigurationProperties("geoji")
public record GeojiProperties(
        @DefaultValue Internal internal,
        @DefaultValue Ai ai,
        String guardrailPolicyVersion
) {

    /** @param serviceToken SERVICE_AUTH_TOKEN. 양방향 같은 값(10 §4.7). 로그 금지 */
    public record Internal(String serviceToken) {
    }

    /** @param baseUrl AI_API_BASE_URL */
    public record Ai(String baseUrl) {
    }
}
