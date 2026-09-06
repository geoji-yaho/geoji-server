package com.ttegeoji.backend.util;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * jsonb 컬럼(Challenge.items, WeeklyAward.statsSnapshot)은 이미 직렬화된 JSON 문자열로 저장된다
 * (Hibernate가 String을 jsonb에 매핑할 때의 규칙). 컨트롤러 입출력에서는 평범한 JSON 객체로
 * 주고받고 싶으므로 여기서 변환한다. Spring Boot 4.1은 Jackson 3(tools.jackson.*)을 쓴다 —
 * 옛 com.fasterxml.jackson과 패키지가 다르니 착각하지 말 것. Spring이 관리하는 ObjectMapper와
 * 별개의 인스턴스라 커스텀 직렬화 설정은 반영되지 않지만, 순수 데이터 JSON에는 필요 없다.
 */
public final class Json {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("JSON 직렬화에 실패했습니다: " + e.getMessage());
        }
    }

    public static Object read(String json) {
        if (json == null) {
            return null;
        }
        try {
            return MAPPER.readValue(json, Object.class);
        } catch (JacksonException e) {
            throw new IllegalStateException("저장된 JSON 파싱에 실패했습니다: " + e.getMessage());
        }
    }
}
