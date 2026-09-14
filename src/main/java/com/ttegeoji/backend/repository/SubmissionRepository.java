package com.ttegeoji.backend.repository;

import com.ttegeoji.backend.domain.Submission;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SubmissionRepository extends JpaRepository<Submission, UUID> {

    // 남의 제출을 id 만으로 집지 않는다. UNIQUE(actor_id, id)
    Optional<Submission> findByIdAndActorId(UUID id, UUID actorId);
}
