package com.ttegeoji.backend.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "profiles")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Profile {

    @Id
    private UUID id; // auth.users(id)와 동일한 값. auth 스키마는 JPA로 매핑하지 않는다.

    @Column(nullable = false)
    private String nickname;

    @Column(name = "avatar_url")
    private String avatarUrl;

    @Column(name = "monthly_budget", nullable = false)
    private Integer monthlyBudget;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    // DB 트리거(set_updated_at)가 갱신을 전담하므로 JPA는 쓰지 않고 읽기만 한다.
    @Column(name = "updated_at", insertable = false, updatable = false)
    private OffsetDateTime updatedAt;
}
