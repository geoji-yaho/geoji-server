package com.ttegeoji.backend.api;

import com.ttegeoji.backend.ai.AiTraceClient;
import com.ttegeoji.backend.repository.PostRepository;
import com.ttegeoji.backend.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

// 데모 C 관측 화면용 trace 조회(10 §4.5). 프록시 주체는 백엔드(§14, 9/14 결정).
// 게시물 작성자만 본다. 없음·삭제·남의 게시물은 같은 404 라 존재 여부가 드러나지 않는다(10 §1).
@Slf4j
@RestController
@RequiredArgsConstructor
public class TraceProxyController {

    private final PostRepository postRepository;
    private final AiTraceClient aiTraceClient;

    @GetMapping("/api/posts/{postId}/trace")
    public ResponseEntity<?> getTrace(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID postId) {
        UUID currentUserId = CurrentUser.idOf(jwt);

        // 조회 트랜잭션은 repository 호출 안에서 끝난다(open-in-view off). AI 호출은 그 밖이다
        boolean isAuthor = postRepository.findById(postId)
                .filter(post -> post.getDeletedAt() == null && post.getAuthorId().equals(currentUserId))
                .isPresent();
        if (!isAuthor) {
            return message(HttpStatus.NOT_FOUND, "게시물을 찾을 수 없습니다.");
        }

        Optional<JsonNode> trace;
        try {
            trace = aiTraceClient.fetch(postId);
        } catch (AiTraceClient.TraceTimeoutException e) {
            log.warn("trace 조회 타임아웃: postId={}", postId);
            return message(HttpStatus.GATEWAY_TIMEOUT, "AI 서버 응답이 늦어 trace 를 불러오지 못했습니다.");
        } catch (AiTraceClient.TraceUnavailableException e) {
            log.warn("trace 조회 실패: postId={}, {}", postId, e.getMessage());
            return message(HttpStatus.BAD_GATEWAY, "AI 서버에서 trace 를 불러오지 못했습니다.");
        }

        return trace.<ResponseEntity<?>>map(body -> ResponseEntity.ok(toCamelCase(body)))
                .orElseGet(() -> message(HttpStatus.NOT_FOUND, "trace 기록이 없습니다."));
    }

    private static ResponseEntity<Map<String, String>> message(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("message", message));
    }

    // AI API 는 snake_case 다. 공개 JSON 은 camelCase(9/15 결정). trace 의 객체 키는 모두 필드 이름이라 값은 건드리지 않는다
    static JsonNode toCamelCase(JsonNode node) {
        if (node.isObject()) {
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            for (Map.Entry<String, JsonNode> entry : node.properties()) {
                out.set(camelCase(entry.getKey()), toCamelCase(entry.getValue()));
            }
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = JsonNodeFactory.instance.arrayNode();
            for (JsonNode element : node) {
                out.add(toCamelCase(element));
            }
            return out;
        }
        return node;
    }

    static String camelCase(String key) {
        StringBuilder out = new StringBuilder(key.length());
        boolean upperNext = false;
        for (char c : key.toCharArray()) {
            if (c == '_') {
                upperNext = !out.isEmpty();
                continue;
            }
            out.append(upperNext ? Character.toUpperCase(c) : c);
            upperNext = false;
        }
        return out.toString();
    }
}
