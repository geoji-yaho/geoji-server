package com.ttegeoji.backend.repository;

import com.ttegeoji.backend.domain.MemeImage;
import com.ttegeoji.backend.domain.enums.MemeTag;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface MemeImageRepository extends JpaRepository<MemeImage, UUID> {

    // 10 §11 후보: tag 일치 ∧ is_active
    List<MemeImage> findByTagAndActiveTrue(MemeTag tag);
}
