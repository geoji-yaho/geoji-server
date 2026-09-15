package com.ttegeoji.backend.domain;

import com.ttegeoji.backend.domain.enums.SpiceLevel;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.generator.EventType;
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
    // @Generated가 없으면 insert 직후 메모리상의 값은 null로 남는다 (Hibernate가 다시 안 읽어옴) —
    // 방 생성 응답에 초대 코드가 바로 실려야 하므로 반드시 필요하다.
    @Generated(event = EventType.INSERT)
    @Column(name = "invite_code", insertable = false, updatable = false)
    private String inviteCode;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE}) // DB 트리거(set_updated_at)가 update마다 다시 채운다
    @Column(name = "updated_at", insertable = false, updatable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;
}
