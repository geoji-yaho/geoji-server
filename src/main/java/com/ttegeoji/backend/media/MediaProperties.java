package com.ttegeoji.backend.media;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.Set;
import java.util.UUID;

/**
 * geoji.media.* 설정(10 §16.5). 값은 환경변수에서 온다(application.yml).
 * 비어 있어도 기동은 하되 업로드는 503 이다 — 짤 업로드가 안 된다고 서비스 전체가 죽으면 안 된다.
 *
 * @param bucket        GEOJI_MEDIA_S3_BUCKET. 비면 업로드 비활성
 * @param region        GEOJI_MEDIA_S3_REGION
 * @param keyPrefix     버킷 안 경로 앞부분. 끝의 / 는 붙이지 않는다
 * @param publicBaseUrl 공개 URL 앞부분(CloudFront 등). 비면 S3 가상호스트 주소를 쓴다
 * @param adminIds      업로드를 허용할 profiles.id 목록. 비면 아무도 못 올린다(10 §16.5)
 */
@ConfigurationProperties("geoji.media")
public record MediaProperties(
        String bucket,
        String region,
        @DefaultValue("memes") String keyPrefix,
        String publicBaseUrl,
        @DefaultValue Set<UUID> adminIds
) {

    public boolean configured() {
        return bucket != null && !bucket.isBlank() && region != null && !region.isBlank();
    }

    public boolean isAdmin(UUID userId) {
        return userId != null && adminIds.contains(userId);
    }

    /** 업로드한 객체를 브라우저가 받을 주소. meme_images.image_url 에 그대로 들어간다 */
    public String publicUrl(String key) {
        if (publicBaseUrl != null && !publicBaseUrl.isBlank()) {
            return publicBaseUrl.replaceAll("/+$", "") + "/" + key;
        }
        return "https://%s.s3.%s.amazonaws.com/%s".formatted(bucket, region, key);
    }
}
