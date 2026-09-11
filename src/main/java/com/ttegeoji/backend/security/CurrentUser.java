package com.ttegeoji.backend.security;

import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Map;
import java.util.UUID;

/** Supabase가 발급한 JWT의 sub 클레임은 auth.users.id(=profiles.id)와 같은 값이다. */
public final class CurrentUser {
    private CurrentUser() {
    }

    public static UUID idOf(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    // 소셜 로그인(카카오 등) 시 Supabase가 JWT의 user_metadata에 provider 프로필을 넣어준다.
    // provider마다 키 이름이 조금씩 다를 수 있어 흔한 후보를 순서대로 확인한다.
    // 이메일/비밀번호 가입처럼 user_metadata가 비어있으면 null을 돌려준다 — 호출부가 폴백을 처리한다.
    public static String nicknameOf(Jwt jwt) {
        return firstNonBlank(jwt, "nickname", "name", "full_name", "user_name", "preferred_username");
    }

    public static String avatarUrlOf(Jwt jwt) {
        return firstNonBlank(jwt, "avatar_url", "picture");
    }

    private static String firstNonBlank(Jwt jwt, String... metadataKeys) {
        Map<String, Object> metadata = jwt.getClaimAsMap("user_metadata");
        if (metadata == null) {
            return null;
        }
        for (String key : metadataKeys) {
            Object value = metadata.get(key);
            if (value instanceof String s && !s.isBlank()) {
                return s;
            }
        }
        return null;
    }
}
