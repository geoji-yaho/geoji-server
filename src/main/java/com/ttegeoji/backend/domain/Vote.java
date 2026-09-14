package com.ttegeoji.backend.domain;

import com.ttegeoji.backend.domain.enums.VerdictType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

// 게시물 투표. 게시물당 1인 1표·수정 불가(9/14 결정). 10 §2 에 없는 테이블(AI 파트 회신 대상)
@Entity
@Table(name = "votes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Vote {

    @Id
    @UuidGenerator
    private UUID id;

    @Column(name = "post_id", nullable = false)
    private UUID postId;

    @Column(name = "voter_id", nullable = false)
    private UUID voterId;

    // 투표한 방. 강도 결정(표 최다 방)에 쓴다
    @Column(name = "room_id", nullable = false)
    private UUID roomId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "verdict")
    private VerdictType verdict;

    // 1~500자 필수
    @Column(nullable = false)
    private String reason;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
