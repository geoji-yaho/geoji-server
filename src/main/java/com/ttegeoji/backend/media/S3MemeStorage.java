package com.ttegeoji.backend.media;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * S3 업로드(10 §16.5). 버킷·리전이 설정된 환경에서만 빈으로 뜬다.
 *
 * <p>자격증명은 코드에 두지 않는다. 기본 제공자 체인이 EB 인스턴스 프로파일에서 읽는다.
 */
@Slf4j
@Component
// 빈 문자열도 "설정됨" 으로 보는 ConditionalOnProperty 로는 구분이 안 된다. 값이 실제로 있는지 본다
@ConditionalOnExpression("'${geoji.media.bucket:}' != '' and '${geoji.media.region:}' != ''")
public class S3MemeStorage implements MemeStorage {

    private final MediaProperties properties;
    private final S3Client client;

    public S3MemeStorage(MediaProperties properties) {
        this.properties = properties;
        this.client = S3Client.builder().region(Region.of(properties.region())).build();
    }

    @Override
    public String put(String key, byte[] bytes, String contentType) {
        try {
            client.putObject(PutObjectRequest.builder()
                            .bucket(properties.bucket())
                            .key(key)
                            .contentType(contentType)
                            .build(),
                    RequestBody.fromBytes(bytes));
        } catch (S3Exception e) {
            // 버킷 권한·이름 문제는 운영 설정 문제다. 사유 원문은 로그에만 남긴다
            log.error("짤 업로드 실패 key={} status={}", key, e.statusCode(), e);
            throw new MediaRejection(HttpStatus.BAD_GATEWAY, "이미지 저장소에 올리지 못했습니다.");
        }
        return properties.publicUrl(key);
    }
}
