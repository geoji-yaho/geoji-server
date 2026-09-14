package com.ttegeoji.backend.repository;

import com.ttegeoji.backend.domain.Post;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PostRepository extends JpaRepository<Post, UUID> {

    // 중복 완료 요청은 기존 post 를 돌려준다(10 §9·§13)
    Optional<Post> findBySubmissionId(UUID submissionId);
}
