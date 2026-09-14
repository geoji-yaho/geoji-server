package com.ttegeoji.backend.domain;

import com.ttegeoji.backend.domain.enums.MemeTag;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.util.UUID;

// 10 §2·§11 meme_images. finalize 가 점수로 고른다
@Entity
@Table(name = "meme_images")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MemeImage {

    @Id
    @UuidGenerator
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MemeTag tag;

    @Builder.Default
    @Column(nullable = false, columnDefinition = "text[]")
    private String[] strategies = new String[0];

    // 감정 6종(10 §11). 값 제한은 DB CHECK
    @Builder.Default
    @Column(nullable = false, columnDefinition = "text[]")
    private String[] emotions = new String[0];

    @Builder.Default
    @Column(nullable = false, columnDefinition = "text[]")
    private String[] keywords = new String[0];

    @Column(name = "image_url", nullable = false)
    private String imageUrl;

    @Builder.Default
    @Column(name = "is_active", nullable = false)
    private Boolean active = true;
}
