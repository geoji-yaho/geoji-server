package com.ttegeoji.backend.domain;

import com.ttegeoji.backend.domain.enums.SpiceLevel;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "rooms")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Room {

    @Id
    @UuidGenerator
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "spice_level", nullable = false, columnDefinition = "spice_level")
    private SpiceLevel spiceLevel;

    @Column(name = "vote_deadline_minutes", nullable = false)
    private Integer voteDeadlineMinutes;

    @Column(name = "rules", columnDefinition = "text[]")
    private String[] rules;

    // gen_random_bytes 기반 DB 기본값을 그대로 쓴다. 앱에서 값을 주면 유니크 제약이 깨질 수 있어 아예 막는다.
    @Column(name = "invite_code", insertable = false, updatable = false)
    private String inviteCode;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private OffsetDateTime updatedAt;
}
