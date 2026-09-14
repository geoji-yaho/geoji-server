package com.ttegeoji.backend.internal.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/** 10 §4.1 CaseSnapshot.privacy_versions[]. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record PrivacyVersion(
        @JsonProperty("scope_key") String scopeKey,
        @JsonProperty("epoch") long epoch) {
}
