package com.ttegeoji.backend.repository;

import com.ttegeoji.backend.domain.VerdictText;
import com.ttegeoji.backend.domain.enums.SpiceLevel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VerdictTextRepository extends JpaRepository<VerdictText, UUID> {
    List<VerdictText> findByVerdictId(UUID verdictId);

    Optional<VerdictText> findByVerdictIdAndIntensity(UUID verdictId, SpiceLevel intensity);
}
