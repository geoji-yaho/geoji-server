package com.ttegeoji.backend.repository;

import com.ttegeoji.backend.domain.MemeImage;
import com.ttegeoji.backend.domain.enums.MemeTag;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MemeImageRepository extends JpaRepository<MemeImage, UUID> {

    // 10 §11 후보: tag 일치 ∧ is_active
    List<MemeImage> findByTagAndActiveTrue(MemeTag tag);

    // 10 §16.5 같은 파일 재업로드는 새 후보를 만들지 않는다. asset_key 는 업로드 바이트의 SHA-256
    Optional<MemeImage> findByAssetKey(String assetKey);
}
