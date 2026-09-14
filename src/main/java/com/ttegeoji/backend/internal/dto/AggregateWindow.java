package com.ttegeoji.backend.internal.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/** 10 §4.2 aggregates.window. 워커는 end_at == 사건 created_at, 길이 정확히 30일이 아니면 집계를 버린다. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AggregateWindow(
        @JsonProperty("start_at") String startAt,
        @JsonProperty("end_at") String endAt) {
}
