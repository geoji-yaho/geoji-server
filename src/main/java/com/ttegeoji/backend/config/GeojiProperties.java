package com.ttegeoji.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.Optional;
import java.util.UUID;

/**
 * geoji.* 설정. 값은 환경변수에서 온다(application.yml). 비어 있어도 기동은 하되,
 * 서비스 토큰이 비면 내부 API 는 전부 401 이다(10 §4.7).
 *
 * @param internal               워커 → 백엔드 내부 API 설정
 * @param ai                     백엔드 → AI API 설정
 * @param guardrailPolicyVersion finalize 가 비교하는 정책 버전(10 §5, §16.1). GUARDRAIL_POLICY_VERSION
 * @param aiJurorUserId          데모 AI 배심원 떼거지봇의 profiles.id(19 §2). GEOJI_AI_JUROR_USER_ID.
 *                               비어 있으면 JURY_VOTE INSERT 를 하지 않고 jury-votes 는 403, ai-member 는 503
 */
@ConfigurationProperties("geoji")
public record GeojiProperties(
        @DefaultValue Internal internal,
        @DefaultValue Ai ai,
        String guardrailPolicyVersion,
        String aiJurorUserId
) {

    /** @param serviceToken SERVICE_AUTH_TOKEN. 양방향 같은 값(10 §4.7). 로그 금지 */
    public record Internal(String serviceToken) {
    }

    /** @param baseUrl AI_API_BASE_URL */
    public record Ai(String baseUrl) {
    }

    /** 떼거지봇 id. 비어 있거나 UUID 가 아니면 empty — 미설정과 같게 다룬다(19 §2). */
    public Optional<UUID> aiJuror() {
        if (aiJurorUserId == null || aiJurorUserId.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(aiJurorUserId.strip()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
