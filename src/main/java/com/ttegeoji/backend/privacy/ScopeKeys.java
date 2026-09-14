package com.ttegeoji.backend.privacy;

import java.util.UUID;

// ai.privacy_epochs.scope_key 모양(10 §2). 워커의 privacy_versions[].scope_key 와 같은 문자열
public final class ScopeKeys {

    private ScopeKeys() {
    }

    public static String user(UUID userId) {
        return "user:" + userId;
    }

    public static String room(UUID roomId) {
        return "room:" + roomId;
    }

    public static String post(UUID postId) {
        return "post:" + postId;
    }
}
