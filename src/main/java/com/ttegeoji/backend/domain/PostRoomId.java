package com.ttegeoji.backend.domain;

import jakarta.persistence.Embeddable;
import lombok.*;

import java.io.Serializable;
import java.util.UUID;

@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class PostRoomId implements Serializable {
    private UUID postId;
    private UUID roomId;
}
