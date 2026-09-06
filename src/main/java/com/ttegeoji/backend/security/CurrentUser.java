package com.ttegeoji.backend.security;

import org.springframework.security.oauth2.jwt.Jwt;

import java.util.UUID;

/** Supabase가 발급한 JWT의 sub 클레임은 auth.users.id(=profiles.id)와 같은 값이다. */
public final class CurrentUser {
    private CurrentUser() {
    }

    public static UUID idOf(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
